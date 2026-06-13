from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    DB_HOST: str = "127.0.0.1"
    DB_PORT: int = 5432
    DB_NAME: str = "scoophub"
    DB_USER: str = "scoophub"
    DB_PASSWORD: str = "changeme"
    PORT: int = 8000
    LOG_LEVEL: str = "info"
    ENABLE_SCHEDULER: bool = True

    # LLM Configuration
    LLM_API_URL: str = "https://openrouter.ai/api/v1"
    LLM_API_KEY: str = ""
    LLM_MODEL: str = "google/gemini-2.0-flash-001"

    # Auth (Google OAuth + JWT)
    ALLOWED_EMAILS: str = ""  # comma-separated allowed emails
    SUPER_EMAILS: str = ""  # comma-separated super emails
    GOOGLE_CLIENT_ID: str = ""
    GOOGLE_CLIENT_SECRET: str = ""
    JWT_SECRET: str = "dev-secret-change-me"
    JWT_EXPIRE_HOURS: int = 24
    AUTH_REDIRECT_URL: str = "http://localhost:3000/auth/callback"
    OAUTH_REDIRECT_URI: str = "http://localhost:8000/api/auth/callback"
    CORS_ORIGINS: str = "http://localhost:3000,http://localhost:8000"

    # External API Keys
    PRODUCTHUNT_TOKEN: str = ""
    REDDIT_CLIENT_ID: str = ""
    REDDIT_CLIENT_SECRET: str = ""
    YOUTUBE_API_KEY: str = ""

    @property
    def database_url(self) -> str:
        return (
            f"postgresql://{self.DB_USER}:{self.DB_PASSWORD}"
            f"@{self.DB_HOST}:{self.DB_PORT}/{self.DB_NAME}"
        )

    @property
    def allowed_emails(self) -> set[str]:
        return {e.strip() for e in self.ALLOWED_EMAILS.split(",") if e.strip()}

    @property
    def super_emails(self) -> set[str]:
        return {e.strip() for e in self.SUPER_EMAILS.split(",") if e.strip()}

    @property
    def cors_origins(self) -> list[str]:
        return [o.strip() for o in self.CORS_ORIGINS.split(",") if o.strip()]

    model_config = {"env_file": (".env.local", ".env"), "env_file_encoding": "utf-8"}


settings = Settings()
