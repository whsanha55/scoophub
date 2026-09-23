// #82 — 기술 지표 한글화 + 설명 매핑
// 각 desc: "무슨 지표 / 값 범위 / 높으면 좋은지 낮으면 좋은지" 1-2문장

export interface IndicatorMeta {
  label: string;
  desc: string;
}

// technical_scores (-2~+2, 양수 = 매수 우호)
const SCORE_META: Record<string, IndicatorMeta> = {
  ma: {
    label: "이동평균",
    desc: "이동평균선(MA) 점수. -2~+2 범위, 양수면 가격이 MA 위에 있어 강세(매수 우호).",
  },
  rsi: {
    label: "RSI",
    desc: "상대강도지수 점수. -2~+2 범위. RSI 30 이하(과매도)이면 양수(매수 우호), 70 이상(과매수)이면 음수.",
  },
  macd: {
    label: "MACD",
    desc: "MACD 점수. -2~+2 범위, 히스토그램이 양수(상승 모멘텀)면 매수 우호.",
  },
  bb: {
    label: "볼린저 밴드",
    desc: "볼린저 밴드 점수. -2~+2 범위. %B가 0 이하(밴드 하단 돌파)면 매수 우호, 1 이상(상단 돌파)이면 과열.",
  },
  stochastic: {
    label: "스토캐스틱",
    desc: "스토캐스틱 %K 점수. -2~+2 범위. 20 이하(과매도)이면 매수 우호, 80 이상(과매수)이면 음수.",
  },
  adx: {
    label: "ADX",
    desc: "추세 강도 점수. -2~+2 범위. ADX 25 이상이면 강한 추세(방향성 있는 시장).",
  },
  vwap: {
    label: "VWAP",
    desc: "거래량가중평균가 점수. -2~+2 범위. 가격이 VWAP 위면 강세(매수 우호).",
  },
};

// technical_details (실제 지표값)
const DETAIL_META: Record<string, IndicatorMeta> = {
  ma5: {
    label: "5일 이동평균",
    desc: "최근 5거래일 종가 평균. 단기 추세 기준선.",
  },
  ma20: {
    label: "20일 이동평균",
    desc: "최근 20거래일 종가 평균. 중기 추세 기준선(볼린저 밴드 중단과 동일).",
  },
  ema12: {
    label: "12일 지수이평",
    desc: "최근 12일 지수가중 이동평균. 최근 가격에 더 큰 가중치.",
  },
  ema26: {
    label: "26일 지수이평",
    desc: "최근 26일 지수가중 이동평균. MACD 계산에 사용.",
  },
  rsi_14: {
    label: "14일 RSI",
    desc: "14일 상대강도지수. 0~100 범위, 30 이하 과매도(매수 우려), 70 이상 과매수.",
  },
  macd_line: {
    label: "MACD 선",
    desc: "MACD 선 (12일 EMA - 26일 EMA). 0 위면 상승 추세.",
  },
  macd_signal: {
    label: "MACD 신호선",
    desc: "MACD 신호선 (9일 EMA). MACD 선이 신호선 위로 돌파하면 매수 신호.",
  },
  macd_histogram: {
    label: "MACD 히스토그램",
    desc: "MACD 선 - 신호선. 양수면 상승 모멘텀(매수 우호), 음수면 하락 모멘텀.",
  },
  bb_upper: {
    label: "볼린저 밴드 상단",
    desc: "20일 MA + 2표준편차. 가격이 상단에 닿으면 과열권.",
  },
  bb_middle: {
    label: "볼린저 밴드 중단",
    desc: "20일 이동평균. 밴드의 중심 추세선.",
  },
  bb_lower: {
    label: "볼린저 밴드 하단",
    desc: "20일 MA - 2표준편차. 가격이 하단에 닿으면 과매도권.",
  },
  bb_width: {
    label: "밴드 폭",
    desc: "볼린저 밴드 폭. 클수록 변동성 큼(돌파 임박 신호).",
  },
  bb_pct_b: {
    label: "%B",
    desc: "볼린저 밴드 내 위치. 0~1이 밴드 내, 1 초과면 상단 돌파, 0 미만이면 하단 돌파.",
  },
  stochastic_k: {
    label: "스토캐스틱 %K",
    desc: "스토캐스틱 %K. 0~100 범위, 20 이하 과매도, 80 이상 과매수.",
  },
  stochastic_d: {
    label: "스토캐스틱 %D",
    desc: "%K의 이동평균. %K가 %D를 상향 돌파하면 매수 신호.",
  },
  adx: {
    label: "ADX",
    desc: "추세 강도 지표. 0~100 범위, 25 이상이면 강한 추세.",
  },
  atr: {
    label: "ATR",
    desc: "평균진폭. 클수록 변동성 큼. 손절가 산출에 사용.",
  },
  obv: {
    label: "OBV",
    desc: "거래량 누적지표. 상승하면 자금 유입(매수 우호), 하락하면 자금 유출.",
  },
  vwap: {
    label: "VWAP",
    desc: "거래량가중평균가. 기관 매매 기준선, 가격이 위면 강세.",
  },
  obv_trend_dir: {
    label: "OBV 추세",
    desc: "OBV 추세 방향. +1 상승(자금 유입), -1 하락(자금 유출), 0 횡보.",
  },
};

const ALL_META: Record<string, IndicatorMeta> = { ...SCORE_META, ...DETAIL_META };

// 매핑 없는 key는 null 반환 → 호출부에서 label=raw key, 툴팁 없음 처리
export function getIndicatorMeta(key: string): IndicatorMeta | null {
  return ALL_META[key] ?? null;
}
