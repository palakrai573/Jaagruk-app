"""Row-level access scope.

Authorisation is never left to a handler body, because that is where it gets forgotten. Every
query that can touch site data takes an :class:`AccessScope` and applies it, so a site officer
cannot read another site's rows even if a new endpoint omits an explicit check.

The scope is derived once, from the caller's token, and then threaded through the service layer.
"""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import Select, select
from sqlalchemy.orm import Session

from app.core.security import Role
from app.models import Site


@dataclass(frozen=True, slots=True)
class AccessScope:
    role: Role
    user_id: str
    username: str
    company_id: str | None
    site_id: str | None

    @property
    def is_global(self) -> bool:
        """DGMS inspectors read across every company; that is the point of the role."""
        return self.role.sees_all_companies

    @property
    def is_site_bound(self) -> bool:
        return not self.is_global and self.site_id is not None

    def visible_site_ids(self, session: Session) -> list[str] | None:
        """Site ids this caller may read, or ``None`` meaning "no restriction"."""
        if self.is_global:
            return None
        if self.site_id is not None:
            return [self.site_id]
        if self.company_id is not None:
            return list(
                session.scalars(
                    select(Site.id).where(Site.company_id == self.company_id)
                ).all()
            )
        # A non-global user with neither a site nor a company can see nothing. Returning an empty
        # list rather than None is deliberate: "restricted to nothing" must never be mistaken for
        # "unrestricted".
        return []

    def may_write_site(self, site_id: str) -> bool:
        if self.role is Role.COMPANY_ADMIN:
            return True
        if self.site_id is not None:
            return site_id == self.site_id
        return False

    def restrict(self, statement: Select, site_column) -> Select:  # noqa: ANN001
        """Apply the scope to a query, given the column holding the site id."""
        if self.is_global:
            return statement
        if self.site_id is not None:
            return statement.where(site_column == self.site_id)
        if self.company_id is not None:
            return statement.where(
                site_column.in_(select(Site.id).where(Site.company_id == self.company_id))
            )
        return statement.where(site_column.is_(None))
