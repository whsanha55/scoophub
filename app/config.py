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

    @property
    def database_url(self) -> str:
        return (
            f"postgresql://{self.DB_USER}:{self.DB_PASSWORD}"
            f"@{self.DB_HOST}:{self.DB_PORT}/{self.DB_NAME}"
        )

    model_config = {"env_file": (".env.local", ".env"), "env_file_encoding": "utf-8"}


settings = Settings()
