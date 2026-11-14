import hashlib
import hmac
import threading
import time
from contextlib import contextmanager

import psycopg
from psycopg import sql
from psycopg.rows import dict_row

from .config import Settings


class Databases:
    SCHEMA_VERSION = 6
    def __init__(self, settings: Settings):
        self.s = settings
        self._ready = set()
        self._provision_lock = threading.Lock()

    def identity(self, uid):
        self.s.user_dir(uid)  # validate before constructing SQL identifiers
        name = f"mes_kb_u_{uid}"
        password = hmac.new(self.s.secret.encode(), f"postgres:{uid}".encode(), hashlib.sha256).hexdigest()
        return name, name, password

    def connect(self, uid):
        database, role, password = self.identity(uid)
        return psycopg.connect(host=self.s.pg_host, port=self.s.pg_port, dbname=database,
                               user=role, password=password, row_factory=dict_row, connect_timeout=5)

    def admin(self, database="postgres"):
        return psycopg.connect(host=self.s.pg_host, port=self.s.pg_port, dbname=database,
                               user=self.s.pg_admin, password=self.s.pg_password,
                               autocommit=True, connect_timeout=5)

    def ensure(self, uid):
        with self._provision_lock:
            if uid in self._ready:
                return
            # Keep the provisioning lock through the complete schema transaction, so another
            # process cannot discover a half-initialized database and run competing DDL.
            with self.admin() as control:
                control.execute("SELECT pg_advisory_lock(728401)")
                try:
                    self._ensure(uid)
                finally:
                    control.execute("SELECT pg_advisory_unlock(728401)")
            self._ready.add(uid)

    def _ensure(self, uid):
        name, role, password = self.identity(uid)
        # Provisioning is serialized across service processes, not just threads.
        with self.admin() as db:
            exists=bool(db.execute("SELECT 1 FROM pg_database WHERE datname=%s", (name,)).fetchone())
            if exists:
                with self.admin(name) as inspect:
                    if inspect.execute("SELECT to_regclass('public.private_schema')").fetchone()[0]:
                        version=inspect.execute("SELECT version FROM private_schema WHERE id=1").fetchone()
                        if version and version[0]==self.SCHEMA_VERSION:
                            return  # Ordinary requests never repeat DDL against a current user database.
            if not db.execute("SELECT 1 FROM pg_roles WHERE rolname=%s", (role,)).fetchone():
                db.execute(sql.SQL("CREATE ROLE {} LOGIN PASSWORD {} NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT")
                           .format(sql.Identifier(role), sql.Literal(password)))
            if not exists:
                db.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(name)))
            db.execute(sql.SQL("REVOKE ALL ON DATABASE {} FROM PUBLIC").format(sql.Identifier(name)))
            db.execute(sql.SQL("GRANT CONNECT, CREATE ON DATABASE {} TO {}")
                       .format(sql.Identifier(name), sql.Identifier(role)))
        with self.admin(name) as db:
            db.execute("CREATE EXTENSION IF NOT EXISTS vector")
            db.execute("CREATE EXTENSION IF NOT EXISTS age")
            db.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC")
            db.execute(sql.SQL("GRANT ALL ON SCHEMA public TO {}").format(sql.Identifier(role)))
            db.execute(sql.SQL("GRANT USAGE ON SCHEMA ag_catalog TO {}").format(sql.Identifier(role)))
            # AGE's graph API reads its own database-local catalog; no elevated role is needed.
            db.execute(sql.SQL("GRANT SELECT ON ag_catalog.ag_graph,ag_catalog.ag_label TO {}")
                       .format(sql.Identifier(role)))
        with self.connect(uid) as db:
            db.execute("""
            CREATE TABLE IF NOT EXISTS private_state (
              id INTEGER PRIMARY KEY CHECK(id=1), generation TEXT, blocked BOOLEAN NOT NULL DEFAULT FALSE,
              revision BIGINT NOT NULL DEFAULT 0,
              preferences JSONB NOT NULL DEFAULT '{"role":"通用岗位","level":"入门","length":"简洁"}');
            INSERT INTO private_state(id) VALUES(1) ON CONFLICT DO NOTHING;
            CREATE TABLE IF NOT EXISTS documents (
              id TEXT PRIMARY KEY, name TEXT NOT NULL, version INTEGER NOT NULL,
              hash TEXT NOT NULL, path TEXT NOT NULL, pieces JSONB NOT NULL, deleted BOOLEAN DEFAULT FALSE,
              created TIMESTAMPTZ DEFAULT now());
            CREATE TABLE IF NOT EXISTS jobs (
              id TEXT PRIMARY KEY, state TEXT NOT NULL, error TEXT, created TIMESTAMPTZ DEFAULT now(),
              finished TIMESTAMPTZ, generation TEXT, revision BIGINT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS document_versions (
              document_id TEXT NOT NULL, version INTEGER NOT NULL, name TEXT NOT NULL,
              hash TEXT NOT NULL, path TEXT NOT NULL, PRIMARY KEY(document_id,version));
            CREATE TABLE IF NOT EXISTS history (
              id BIGSERIAL PRIMARY KEY, question TEXT, response JSONB, generation TEXT,
              created TIMESTAMPTZ DEFAULT now());
            CREATE TABLE IF NOT EXISTS face_template (
              id INTEGER PRIMARY KEY CHECK(id=1), model TEXT NOT NULL, dimension INTEGER NOT NULL,
              ciphertext BYTEA NOT NULL, updated TIMESTAMPTZ DEFAULT now());
            CREATE TABLE IF NOT EXISTS face_challenge (
              id TEXT PRIMARY KEY, purpose TEXT NOT NULL, sequence JSONB NOT NULL,
              expires DOUBLE PRECISION NOT NULL, used BOOLEAN NOT NULL DEFAULT FALSE);
            CREATE TABLE IF NOT EXISTS face_capture (digest TEXT PRIMARY KEY, created TIMESTAMPTZ DEFAULT now());
            CREATE TABLE IF NOT EXISTS pending_files (path TEXT PRIMARY KEY);
            CREATE TABLE IF NOT EXISTS audit (
              id BIGSERIAL PRIMARY KEY, action TEXT NOT NULL, actor BIGINT NOT NULL,
              created TIMESTAMPTZ DEFAULT now());
            ALTER TABLE private_state ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 0;
            ALTER TABLE jobs ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 0;
            ALTER TABLE jobs ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE face_capture ADD COLUMN IF NOT EXISTS fingerprint BYTEA;
            ALTER TABLE private_state ADD COLUMN IF NOT EXISTS graph_revision BIGINT NOT NULL DEFAULT -1;
            ALTER TABLE private_state ADD COLUMN IF NOT EXISTS model_consent BOOLEAN NOT NULL DEFAULT FALSE;
            ALTER TABLE private_state ADD COLUMN IF NOT EXISTS graph_enabled BOOLEAN NOT NULL DEFAULT FALSE;
            ALTER TABLE private_state ADD COLUMN IF NOT EXISTS answer_revision BIGINT NOT NULL DEFAULT 0;
            ALTER TABLE documents ADD COLUMN IF NOT EXISTS vector_version INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE documents ADD COLUMN IF NOT EXISTS vector_state TEXT NOT NULL DEFAULT 'queued';
            ALTER TABLE documents ADD COLUMN IF NOT EXISTS vector_error TEXT;
            ALTER TABLE documents ADD COLUMN IF NOT EXISTS graph_state TEXT NOT NULL DEFAULT 'not_requested';
            ALTER TABLE documents ADD COLUMN IF NOT EXISTS active_hash TEXT;
            ALTER TABLE documents ADD COLUMN IF NOT EXISTS active_name TEXT;
            ALTER TABLE documents ADD COLUMN IF NOT EXISTS active_pieces JSONB NOT NULL DEFAULT '[]';
            UPDATE documents SET active_hash=hash,active_name=name,active_pieces=pieces
                WHERE NOT deleted AND vector_version=version AND vector_version>0 AND active_hash IS NULL;
            ALTER TABLE document_versions ADD COLUMN IF NOT EXISTS size_bytes BIGINT NOT NULL DEFAULT 0;
            ALTER TABLE document_versions ADD COLUMN IF NOT EXISTS extracted_pieces JSONB;
            CREATE TABLE IF NOT EXISTS ocr_reviews (
              id TEXT PRIMARY KEY, name TEXT NOT NULL, hash TEXT NOT NULL, document_id TEXT,
              base_version INTEGER, pages JSONB NOT NULL, state TEXT NOT NULL DEFAULT 'prepared', receipt JSONB,
              expires TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '30 minutes'));
            ALTER TABLE ocr_reviews ADD COLUMN IF NOT EXISTS state TEXT NOT NULL DEFAULT 'prepared';
            ALTER TABLE ocr_reviews ADD COLUMN IF NOT EXISTS receipt JSONB;
            ALTER TABLE jobs ADD COLUMN IF NOT EXISTS vector_state TEXT NOT NULL DEFAULT 'queued';
            ALTER TABLE jobs ADD COLUMN IF NOT EXISTS graph_state TEXT NOT NULL DEFAULT 'not_requested';
            ALTER TABLE jobs ADD COLUMN IF NOT EXISTS graph_requested BOOLEAN NOT NULL DEFAULT FALSE;
            ALTER TABLE jobs ADD COLUMN IF NOT EXISTS started TIMESTAMPTZ;
            ALTER TABLE history ADD COLUMN IF NOT EXISTS valid BOOLEAN NOT NULL DEFAULT TRUE;
            ALTER TABLE history ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 0;
            ALTER TABLE history ADD COLUMN IF NOT EXISTS session_id TEXT NOT NULL DEFAULT 'default';
            ALTER TABLE face_template ADD COLUMN IF NOT EXISTS embedding vector(128);
            CREATE TABLE IF NOT EXISTS knowledge_chunks (
              id TEXT PRIMARY KEY, document_id TEXT NOT NULL REFERENCES documents(id),
              document_version INTEGER NOT NULL, document_hash TEXT NOT NULL,
              locator TEXT NOT NULL, content TEXT NOT NULL, embedding vector(512) NOT NULL,
              model TEXT NOT NULL, created TIMESTAMPTZ NOT NULL DEFAULT now());
            CREATE INDEX IF NOT EXISTS knowledge_chunk_source
              ON knowledge_chunks(document_id,document_version);
            CREATE TABLE IF NOT EXISTS graph_profiles (
                generation TEXT PRIMARY KEY,signature TEXT NOT NULL,reused_extractions INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS private_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL);
            """)
            db.execute("""INSERT INTO private_schema(id,version) VALUES(1,%s)
                ON CONFLICT(id) DO UPDATE SET version=excluded.version""", (self.SCHEMA_VERSION,))

    @contextmanager
    def index_lease(self, uid):
        """Cross-process ownership for indexing, separate from short user mutations."""
        with self.connect(uid) as db:
            acquired = db.execute('SELECT pg_try_advisory_lock(728406) AS acquired').fetchone()['acquired']
            db.commit()
            try:
                yield acquired
            finally:
                if acquired:
                    db.execute('SELECT pg_advisory_unlock(728406)')

    @contextmanager
    def locked(self, uid):
        """Mutations, retrieval and generation switches share one per-user DB lock."""
        with self.connect(uid) as db:
            deadline=time.monotonic()+2
            while not db.execute("SELECT pg_try_advisory_lock(728402) AS acquired").fetchone()["acquired"]:
                db.rollback()
                if time.monotonic()>=deadline:
                    raise ValueError("资料状态正在更新，请稍后重试")
                time.sleep(0.05)
            db.commit()
            try:
                yield db
                db.commit()
            finally:
                db.rollback()
                db.execute("SELECT pg_advisory_unlock(728402)")
                db.commit()

    def users(self):
        with self.admin() as db:
            rows = db.execute("SELECT datname FROM pg_database WHERE datname ~ '^mes_kb_u_[0-9]+$'").fetchall()
        return [int(row[0].rsplit('_',1)[1]) for row in rows]

    def worker_env(self, uid):
        name, role, password = self.identity(uid)
        return {"POSTGRES_HOST": self.s.pg_host, "POSTGRES_PORT": str(self.s.pg_port),
                "POSTGRES_USER": role, "POSTGRES_PASSWORD": password, "POSTGRES_DATABASE": name,
                "POSTGRES_MAX_CONNECTIONS": "3", "POSTGRES_ENABLE_VECTOR": "true"}
