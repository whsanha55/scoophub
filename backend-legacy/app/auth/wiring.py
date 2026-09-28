# auth/wiring.py
from __future__ import annotations

import logging
from typing import ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class AuthModule(BaseModule):
    domain_name = "auth"
    router_module = "app.auth.router"
    scheduler_module = None
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "Auth", "description": "Google OAuth 인증 API"},
    ]


register = AuthModule.register
TAGS = AuthModule.tags
