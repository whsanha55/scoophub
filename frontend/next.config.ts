import type { NextConfig } from "next";

const API_URL = process.env.API_URL || "http://localhost:20010";

const nextConfig: NextConfig = {
  output: "standalone",
  async redirects() {
    return [
      // Dashboard 제거 — 루트는 News로.
      { source: "/", destination: "/news", permanent: false },
      // 제거된 보너스 좌석 페이지의 기존 북마크도 뉴스로 이동.
      { source: "/kal-bonus", destination: "/news", permanent: true },
    ];
  },
  async rewrites() {
    return [
      // /api/:path* 프록시 + Bearer 주입은 src/proxy.ts가 담당.
      {
        source: "/docs",
        destination: `${API_URL}/docs`,
      },
      {
        source: "/docs/:path*",
        destination: `${API_URL}/docs/:path*`,
      },
      {
        source: "/openapi.json",
        destination: `${API_URL}/openapi.json`,
      },
    ];
  },
};

export default nextConfig;
