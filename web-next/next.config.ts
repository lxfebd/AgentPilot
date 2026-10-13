import type { NextConfig } from "next";

const apiTarget = (process.env.AGENT_PILOT_API ?? "http://127.0.0.1:8000").trim();
const apiNamespace = process.env.AGENT_PILOT_API_NAMESPACE ?? "/api/v2";
const proxyClientMaxBodySize = 100 * 1024 * 1024;
const staticExport = process.env.NEXT_OUTPUT === "export";
const browserApiTarget = staticExport ? "" : apiTarget;
const apiRoutePrefixes = [
  "/admin",
  "/agents",
  "/auth",
  "/connector",
  "/connectors",
  "/health",
  "/oauth",
  "/pairing",
  "/sessions",
  "/.well-known",
];

const nextConfig: NextConfig = {
  devIndicators: false,
  allowedDevOrigins: ["**.*", "localhost", "*.localhost"],
  output: staticExport ? "export" : undefined,
  trailingSlash: staticExport,
  env: {
    NEXT_PUBLIC_AGENT_PILOT_API: browserApiTarget,
    NEXT_PUBLIC_AGENT_PILOT_API_NAMESPACE: apiNamespace,
  },
  experimental: {
    // Full timeline sync uses HTTP ingest through this same-origin proxy.
    proxyClientMaxBodySize,
  },
  // Without an API target there is nothing to proxy: skip same-origin rewrites.
  ...(staticExport || !apiTarget
    ? {}
    : {
        async rewrites() {
          const namespace = normalizeApiNamespace(apiNamespace);
          if (namespace) {
            const source = `${namespace}/:path*`;
            return [{ source, destination: `${apiTarget}${source}` }];
          }
          return apiRoutePrefixes.map((prefix) => ({
            source: `${prefix}/:path*`,
            destination: `${apiTarget}${prefix}/:path*`
          }));
        }
      })
};

function normalizeApiNamespace(value: string): string {
  const trimmed = value.trim();
  if (!trimmed || trimmed === "/") return "";
  return `/${trimmed.replace(/^\/+|\/+$/g, "")}`;
}

export default nextConfig;
