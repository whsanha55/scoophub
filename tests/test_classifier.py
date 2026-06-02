# tests/test_classifier.py
import pytest
from app.news.classifier import NewsClassifier


@pytest.fixture
def classifier():
    return NewsClassifier()


def test_classify_politics(classifier):
    assert classifier.classify_category("대통령 국회 연설") == "politics"
    assert classifier.classify_category("외교부 북한 회담") == "politics"


def test_classify_economy(classifier):
    assert classifier.classify_category("금리 인하 코스피 급등") == "economy"
    assert classifier.classify_category("Fed 기준금리 결정") == "economy"


def test_classify_tech(classifier):
    assert classifier.classify_category("AI 반도체 실리콘밸리") == "tech"
    assert classifier.classify_category("오픈AI 새 모델 발표") == "tech"


def test_classify_disaster(classifier):
    assert classifier.classify_category("지진 태풍 피해") == "disaster"
    assert classifier.classify_category("대형사고 화재 발생") == "disaster"


def test_classify_global(classifier):
    assert classifier.classify_category("NATO EU 정상회의") == "global"
    assert classifier.classify_category("러시아 우크라이나 전쟁") == "global"


def test_classify_unknown(classifier):
    assert classifier.classify_category("일반 뉴스 아무내용") is None


def test_classify_importance_high(classifier):
    assert classifier.classify_importance("지진 대통령 급락") == "high"


def test_classify_importance_medium(classifier):
    assert classifier.classify_importance("GDP 실업률 반도체") == "medium"


def test_classify_importance_low(classifier):
    result = classifier.classify_importance("일반 경제 동향 전망")
    assert result == "low"


def test_should_exclude(classifier):
    assert classifier.should_exclude("스포츠 축구 결승전") is True
    assert classifier.should_exclude("연예인 아이돌 예능") is True
    assert classifier.should_exclude("대통령 국회 연설") is False


def test_classify_full(classifier):
    result = classifier.classify("대통령 국회 외교 회담")
    assert result.category == "politics"
    assert result.importance == "high"


def test_classify_excluded_returns_none(classifier):
    result = classifier.classify("스포츠 축구 결승전 결과")
    assert result is None
