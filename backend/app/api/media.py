"""Hazard photo and voice-note storage.

Filesystem-backed, and named as such: production wants S3 or MinIO behind the same two endpoints.
Files are stored under a content-hash path, never under a client-supplied name, which removes the
entire path-traversal class of bug rather than trying to sanitise around it.
"""

from __future__ import annotations

import hashlib
import logging
import uuid
from pathlib import Path

from fastapi import APIRouter, File, HTTPException, Query, UploadFile, status
from fastapi.responses import FileResponse
from sqlalchemy import select

from app.api.deps import ClientIpDep, DbDep, ScopeDep, SettingsDep
from app.models import Media, MediaKind
from app.schemas import MediaOut
from app.services import audit

logger = logging.getLogger("jaagruk.media")

router = APIRouter(prefix="/media", tags=["media"])

ALLOWED_PHOTO_TYPES = {"image/jpeg", "image/png", "image/webp"}
ALLOWED_VOICE_TYPES = {
    "audio/mp4",
    "audio/aac",
    "audio/mpeg",
    "audio/ogg",
    "audio/3gpp",
    "audio/amr",
}
#: Read in chunks so an oversized upload is rejected before it is fully buffered in memory.
CHUNK_BYTES = 64 * 1024


@router.post(
    "", response_model=MediaOut, status_code=status.HTTP_201_CREATED, summary="Upload media"
)
async def upload_media(
    db: DbDep,
    settings: SettingsDep,
    scope: ScopeDep,
    ip: ClientIpDep,
    kind: MediaKind = Query(description="photo or voice"),
    site_id: str | None = Query(default=None, max_length=16),
    file: UploadFile = File(...),
) -> MediaOut:
    allowed = ALLOWED_PHOTO_TYPES if kind is MediaKind.PHOTO else ALLOWED_VOICE_TYPES
    content_type = (file.content_type or "").split(";")[0].strip().lower()
    if content_type not in allowed:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=(
                f"'{content_type or 'unknown'}' is not accepted for {kind.value}. "
                f"Allowed: {', '.join(sorted(allowed))}."
            ),
        )

    digest = hashlib.sha256()
    size = 0
    chunks: list[bytes] = []

    while True:
        chunk = await file.read(CHUNK_BYTES)
        if not chunk:
            break
        size += len(chunk)
        if size > settings.max_media_bytes:
            raise HTTPException(
                status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                detail=(
                    f"File exceeds the {settings.max_media_bytes // 1024} KB limit. "
                    "Compress the photo or shorten the voice note."
                ),
            )
        digest.update(chunk)
        chunks.append(chunk)

    if size == 0:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="The uploaded file is empty."
        )

    sha256_hex = digest.hexdigest()

    # Content addressed, so the same photo relayed by two phones costs one copy on disk.
    existing = db.scalar(select(Media).where(Media.sha256_hex == sha256_hex))
    if existing is not None:
        return MediaOut(
            id=existing.id,
            kind=MediaKind(existing.kind),
            content_type=existing.content_type,
            byte_size=existing.byte_size,
            sha256_hex=existing.sha256_hex,
            url=f"{settings.api_prefix}/media/{existing.id}",
        )

    media_id = str(uuid.uuid4())
    # Path built entirely from the digest: no client-supplied component reaches the filesystem.
    relative = Path(kind.value) / sha256_hex[:2] / sha256_hex[2:4] / f"{sha256_hex}.bin"
    destination = (settings.media_root / relative).resolve()
    root = settings.media_root.resolve()
    if not str(destination).startswith(str(root)):
        # Unreachable given the construction above; kept as a hard stop so a future change to the
        # path scheme cannot quietly introduce a traversal.
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Refusing to write outside the media root.",
        )

    try:
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("wb") as handle:
            for chunk in chunks:
                handle.write(chunk)
    except OSError as exc:
        logger.error("media write failed: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_507_INSUFFICIENT_STORAGE,
            detail=(
                "Could not store the file. The hazard report itself can still be submitted "
                "without media."
            ),
        ) from exc

    media = Media(
        id=media_id,
        kind=kind.value,
        content_type=content_type,
        byte_size=size,
        sha256_hex=sha256_hex,
        stored_path=str(relative).replace("\\", "/"),
        site_id=site_id,
        uploaded_by=scope.username,
    )
    db.add(media)
    db.flush()

    audit.record(
        db,
        actor=scope.username,
        actor_role=scope.role.value,
        action=audit.ACTION_MEDIA_UPLOAD,
        target_type="media",
        target_id=media.id,
        detail=f"kind={kind.value} bytes={size}",
        ip_address=ip,
        site_id=site_id,
    )

    return MediaOut(
        id=media.id,
        kind=kind,
        content_type=content_type,
        byte_size=size,
        sha256_hex=sha256_hex,
        url=f"{settings.api_prefix}/media/{media.id}",
    )


@router.get("/{media_id}", summary="Download media")
def download_media(
    media_id: str, db: DbDep, settings: SettingsDep, scope: ScopeDep
) -> FileResponse:
    media = db.get(Media, media_id)
    if media is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=f"Media '{media_id}' was not found."
        )

    if media.site_id is not None:
        visible = scope.visible_site_ids(db)
        if visible is not None and media.site_id not in visible:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail=f"Media '{media_id}' was not found."
            )

    path = (settings.media_root / media.stored_path).resolve()
    if not path.is_file():
        # The row exists but the blob is gone: a restored database against fresh storage, or a
        # partial backup. Say which, rather than returning a bare 404 that looks like a bad id.
        logger.error("media row %s has no file at %s", media_id, path)
        raise HTTPException(
            status_code=status.HTTP_410_GONE,
            detail="This file is recorded but its contents are no longer on disk.",
        )
    return FileResponse(path, media_type=media.content_type, filename=f"{media.id}")
