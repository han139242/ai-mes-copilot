import os
from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    root: Path = field(default_factory=lambda: Path(os.environ.get("PRIVATE_ASSISTANT_ROOT", "D:/AI-MES-Stage2/data")))
    secret: str = field(default_factory=lambda: os.environ.get("PRIVATE_ASSISTANT_SECRET", ""))
    pg_host: str = field(default_factory=lambda: os.environ.get("PRIVATE_PG_HOST", "127.0.0.1"))
    pg_port: int = field(default_factory=lambda: int(os.environ.get("PRIVATE_PG_PORT", "55432")))
    pg_admin: str = field(default_factory=lambda: os.environ.get("PRIVATE_PG_ADMIN", "postgres"))
    pg_password: str = field(default_factory=lambda: os.environ.get("PRIVATE_PG_PASSWORD", ""))
    llm_url: str = field(default_factory=lambda: os.environ.get("PRIVATE_LLM_URL", ""))
    llm_key: str = field(default_factory=lambda: os.environ.get("PRIVATE_LLM_KEY", ""))
    llm_model: str = field(default_factory=lambda: os.environ.get("PRIVATE_LLM_MODEL", ""))
    # 0 disables the optional local call cap; the durable request journal is never reset.
    llm_limit: int = field(default_factory=lambda: int(os.environ.get("PRIVATE_LLM_LIMIT", "0")))
    embedding_model: str = "BAAI/bge-small-zh-v1.5"
    embedding_dim: int = 512
    max_file_bytes: int = 8 * 1024 * 1024
    max_text_chars: int = 240_000

    def validate(self):
        if self.llm_limit < 0:
            raise RuntimeError("PRIVATE_LLM_LIMIT must be zero (no local cap) or a positive integer")
        if len(self.secret.encode()) < 32:
            raise RuntimeError("PRIVATE_ASSISTANT_SECRET must contain at least 32 bytes")
        if not self.pg_password:
            raise RuntimeError("PRIVATE_PG_PASSWORD is required")
        self.root.mkdir(parents=True, exist_ok=True)

    def user_dir(self, uid: int) -> Path:
        if isinstance(uid, bool) or not isinstance(uid, int) or uid <= 0:
            raise ValueError("Invalid trusted MES user id")
        path = self.root / "users" / str(uid)
        path.mkdir(parents=True, exist_ok=True)
        return path
