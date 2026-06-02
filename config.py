from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    DB_HOST: str = "127.0.0.1"
    DB_PORT: int = 5432
    DB_NAME: str = "scoophub"
    DB_USER: str = "scoophub"
    DB_PASSWORD: str = "changeme"
    PORT: int = 8000
    LOG_LEVEL: str = "info"

    @property
    def database_url(self) -> str:
        return (
            f"postgresql://{self.DB_USER}:{self.DB_PASSWORD}"
            f"@{self.DB_HOST}:{self.DB_PORT}/{self.DB_NAME}"
        )

    model_config = {"env_file": (".env.local", ".env"), "env_file_encoding": "utf-8"}


settings = Settings()
