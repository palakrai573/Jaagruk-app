"""Sites, site signing keys, devices and the module catalog."""

from __future__ import annotations

import logging
import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from sqlalchemy import select

from app.api.deps import (
    ClientIpDep,
    DbDep,
    ScopeDep,
    require_roles,
    require_site_access,
)
from app.api.serializers import device_out, module_out, site_key_out, site_out
from app.core.security import Role
from app.db.base import utcnow
from app.models import Company, Device, ModuleRecord, Site, SiteKey
from app.schemas import (
    DeviceOut,
    DeviceRegisterRequest,
    ModuleOut,
    SiteCreate,
    SiteOut,
    SitePublicKeysResponse,
)
from app.services import audit

logger = logging.getLogger("jaagruk.catalog")

router = APIRouter(tags=["catalog"])


# ---------------------------------------------------------------------------
# Sites
# ---------------------------------------------------------------------------


@router.get("/sites", response_model=list[SiteOut], summary="List sites in scope")
def list_sites(db: DbDep, scope: ScopeDep) -> list[SiteOut]:
    statement = select(Site).order_by(Site.id)
    statement = scope.restrict(statement, Site.id)
    return [site_out(site) for site in db.scalars(statement).all()]


@router.get("/sites/{site_id}", response_model=SiteOut, summary="One site")
def get_site(site_id: str, db: DbDep, scope: ScopeDep) -> SiteOut:
    return site_out(require_site_access(db, scope, site_id))


@router.post(
    "/sites",
    response_model=SiteOut,
    status_code=status.HTTP_201_CREATED,
    summary="Create a site",
)
def create_site(
    payload: SiteCreate,
    db: DbDep,
    ip: ClientIpDep,
    scope: Annotated[object, Depends(require_roles(Role.COMPANY_ADMIN))],
) -> SiteOut:
    company = db.get(Company, payload.company_id)
    if company is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Company '{payload.company_id}' was not found.",
        )
    # A company admin can only create sites inside their own company, whatever the payload says.
    if scope.company_id is not None and company.id != scope.company_id:  # type: ignore[attr-defined]
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only create sites within your own company.",
        )
    if db.get(Site, payload.id) is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Site '{payload.id}' already exists.",
        )

    site = Site(
        id=payload.id,
        company_id=payload.company_id,
        name=payload.name,
        district=payload.district,
        sector=payload.sector.value,
        latitude=payload.latitude,
        longitude=payload.longitude,
    )
    db.add(site)
    db.flush()

    audit.record(
        db,
        actor=scope.username,  # type: ignore[attr-defined]
        actor_role=scope.role.value,  # type: ignore[attr-defined]
        action=audit.ACTION_SITE_CREATE,
        target_type="site",
        target_id=site.id,
        ip_address=ip,
        site_id=site.id,
    )
    return site_out(site)


@router.get(
    "/sites/{site_id}/public-keys",
    response_model=SitePublicKeysResponse,
    summary="Every Ed25519 key epoch for a site",
)
def site_public_keys(site_id: str, db: DbDep, scope: ScopeDep) -> SitePublicKeysResponse:
    """All epochs, not just the active one.

    A verifier needs every key a site has ever used, because a certificate issued under a previous
    key must stay verifiable forever. Rotating a key after a supervisor phone is lost is not a
    reason to invalidate the history behind it.
    """
    site = require_site_access(db, scope, site_id)
    keys = db.scalars(
        select(SiteKey).where(SiteKey.site_id == site.id).order_by(SiteKey.epoch.desc())
    ).all()
    return SitePublicKeysResponse(
        site_id=site.id, keys=[site_key_out(key) for key in keys]
    )


# ---------------------------------------------------------------------------
# Devices
# ---------------------------------------------------------------------------


@router.post(
    "/devices/register",
    response_model=DeviceOut,
    status_code=status.HTTP_201_CREATED,
    summary="Register a handset and its site signing key",
)
def register_device(
    payload: DeviceRegisterRequest,
    db: DbDep,
    ip: ClientIpDep,
    scope: Annotated[
        object,
        Depends(require_roles(Role.SUPERVISOR, Role.SITE_OFFICER, Role.COMPANY_ADMIN)),
    ],
) -> DeviceOut:
    """Enrol a device and publish the site public key it will sign certificates with.

    Two distinct trust facts are recorded here. The **site key** is the Ed25519 identity that signs
    certificates and is what every verifier needs. The **attestation key** is a hardware-backed
    Keystore key unique to this handset and signs sync uploads. Keeping them separate means a
    leaked login cannot forge certificates without also having the specific device.

    Re-registering the same device with the same key is idempotent: a reinstall must not lock a
    supervisor out of their own site.
    """
    site = require_site_access(db, scope, payload.site_id)
    if not scope.may_write_site(site.id):  # type: ignore[attr-defined]
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"You cannot register devices for site '{site.id}'.",
        )

    public_key = bytes.fromhex(payload.site_public_key_hex)
    attest_key = (
        bytes.fromhex(payload.attest_public_key_hex)
        if payload.attest_public_key_hex
        else None
    )

    device = db.get(Device, payload.device_id)
    if device is None:
        device = Device(id=payload.device_id, site_id=site.id)
        db.add(device)
    elif device.site_id != site.id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"Device '{payload.device_id}' is already registered to site "
                f"'{device.site_id}'. Deactivate it there before moving it."
            ),
        )

    device.attest_public_key = attest_key or device.attest_public_key
    device.model = payload.model or device.model
    device.android_release = payload.android_release or device.android_release
    device.app_version = payload.app_version or device.app_version
    device.registered_by_user_id = scope.user_id  # type: ignore[attr-defined]
    device.active = True
    device.last_seen_at = utcnow()

    existing_key = db.scalar(
        select(SiteKey).where(
            SiteKey.site_id == site.id, SiteKey.epoch == payload.key_epoch
        )
    )
    if existing_key is None:
        db.add(
            SiteKey(
                id=str(uuid.uuid4()),
                site_id=site.id,
                epoch=payload.key_epoch,
                public_key=public_key,
                active=True,
                registered_by_device_id=device.id,
            )
        )
    elif existing_key.public_key != public_key:
        # A different key claiming the same epoch would make verification ambiguous: two
        # certificates at the same seq could both "verify" against different keys.
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"Site '{site.id}' already has a different key registered at epoch "
                f"{payload.key_epoch}. Register the new key under the next epoch "
                f"({payload.key_epoch + 1}) so existing certificates stay verifiable."
            ),
        )

    db.flush()
    audit.record(
        db,
        actor=scope.username,  # type: ignore[attr-defined]
        actor_role=scope.role.value,  # type: ignore[attr-defined]
        action=audit.ACTION_DEVICE_REGISTER,
        target_type="device",
        target_id=device.id,
        detail=f"site={site.id} epoch={payload.key_epoch}",
        ip_address=ip,
        site_id=site.id,
    )
    return device_out(device)


@router.get("/devices", response_model=list[DeviceOut], summary="List registered devices")
def list_devices(db: DbDep, scope: ScopeDep) -> list[DeviceOut]:
    statement = select(Device).order_by(Device.site_id, Device.id)
    statement = scope.restrict(statement, Device.site_id)
    return [device_out(device) for device in db.scalars(statement).all()]


# ---------------------------------------------------------------------------
# Modules
# ---------------------------------------------------------------------------


@router.get("/modules", response_model=list[ModuleOut], summary="Module catalog")
def list_modules(
    db: DbDep,
    response: Response,
    include_disabled: Annotated[bool, Query()] = False,
) -> list[ModuleOut]:
    """The catalog, with an ETag so devices can skip an unchanged download.

    Unauthenticated reads are not permitted, but the content is not sensitive — it is the same
    catalog compiled into the APK. The ETag exists because a device on a metered or intermittent
    link should not re-download it on every bootstrap.
    """
    statement = select(ModuleRecord).order_by(ModuleRecord.module_code)
    if not include_disabled:
        statement = statement.where(ModuleRecord.enabled.is_(True))
    modules = list(db.scalars(statement).all())

    version = max((m.catalog_version for m in modules), default=0)
    response.headers["ETag"] = f'W/"catalog-{version}-{len(modules)}"'
    response.headers["Cache-Control"] = "private, max-age=300"
    return [module_out(module) for module in modules]
