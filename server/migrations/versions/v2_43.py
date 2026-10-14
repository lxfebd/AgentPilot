"""Record why a schedule's last attempt failed.

`next_run_at` advancing proves the executor reached the row, not that the run
succeeded: a connector that was offline, or a session that had been deleted,
left the schedule looking healthy while nothing ever ran.  The executor now
writes the failure reason and its timestamp onto the row, and clears both on a
successful run or when the schedule is edited.

Revision ID: v2_43
Revises: v2_42
"""

import sqlalchemy as sa
from alembic import op

revision = "v2_43"
down_revision = "v2_42"
branch_labels = None
depends_on = None

TABLE = "session_schedules"

COLUMNS = ("last_error", "last_error_at")


def _column_names() -> set[str]:
    return {column["name"] for column in sa.inspect(op.get_bind()).get_columns(TABLE)}


def upgrade() -> None:
    existing = _column_names()
    for name in COLUMNS:
        if name in existing:
            continue
        op.add_column(TABLE, sa.Column(name, sa.Text(), nullable=True))


def downgrade() -> None:
    existing = _column_names()
    for name in reversed(COLUMNS):
        if name not in existing:
            continue
        op.drop_column(TABLE, name)
