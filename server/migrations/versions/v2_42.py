"""Add session schedules for timed and recurring agent tasks.

A schedule either targets an existing session or creates a new session in a
project when due. `next_run_at` is stored in UTC ISO-8601 so the executor can
scan due rows with a single index regardless of the schedule's own timezone.

Revision ID: v2_42
Revises: v2_41
"""

import sqlalchemy as sa
from alembic import op

revision = "v2_42"
down_revision = "v2_41"
branch_labels = None
depends_on = None

TABLE = "session_schedules"


def _table_names() -> set[str]:
    return set(sa.inspect(op.get_bind()).get_table_names())


def upgrade() -> None:
    if TABLE in _table_names():
        return
    op.create_table(
        TABLE,
        sa.Column("id", sa.Text(), nullable=False, primary_key=True),
        sa.Column(
            "user_id",
            sa.Text(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("title", sa.Text(), nullable=False),
        sa.Column("prompt", sa.Text(), nullable=False),
        sa.Column("project_id", sa.Text(), nullable=True),
        sa.Column("connector_id", sa.Text(), nullable=True),
        sa.Column("runtime", sa.Text(), nullable=True),
        sa.Column("cwd", sa.Text(), nullable=True),
        sa.Column("session_id", sa.Text(), nullable=True),
        sa.Column("schedule_kind", sa.Text(), nullable=False),
        sa.Column("run_at", sa.Text(), nullable=True),
        sa.Column("run_time", sa.Text(), nullable=True),
        sa.Column("weekday", sa.Integer(), nullable=True),
        sa.Column("timezone", sa.Text(), nullable=False, server_default="UTC"),
        sa.Column("enabled", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("last_run_at", sa.Text(), nullable=True),
        sa.Column("next_run_at", sa.Text(), nullable=True),
        sa.Column("created_at", sa.Text(), nullable=False),
        sa.Column("updated_at", sa.Text(), nullable=False),
    )
    op.create_index("ix_session_schedules_user", TABLE, ["user_id"])
    op.create_index("ix_session_schedules_due", TABLE, ["enabled", "next_run_at"])


def downgrade() -> None:
    if TABLE not in _table_names():
        return
    op.drop_index("ix_session_schedules_due", table_name=TABLE)
    op.drop_index("ix_session_schedules_user", table_name=TABLE)
    op.drop_table(TABLE)
