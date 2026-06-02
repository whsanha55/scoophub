from datetime import datetime, timezone

from shared.models import ApiResponse, ErrorDetail, ErrorBody


def test_success_response():
    resp = ApiResponse(success=True, data={"key": "value"})
    assert resp.success is True
    assert resp.data == {"key": "value"}
    assert resp.error is None
    assert resp.meta.requested_at is not None


def test_error_response():
    err = ErrorBody(
        code="INVALID_PARAM",
        message="Invalid parameter",
        detail="minutes must be positive",
        suggestion="Use a positive integer for minutes",
    )
    resp = ApiResponse(success=False, data=None, error=err)
    assert resp.success is False
    assert resp.error.code == "INVALID_PARAM"


def test_meta_defaults():
    resp = ApiResponse(success=True, data=[])
    assert resp.meta.total is None
    assert resp.meta.requested_at is not None
