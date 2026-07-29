import type { NextConfig } from "next";

const backendOrigin = process.env.NEXT_PUBLIC_BACKEND_ORIGIN?.replace(/\/+$/, "");
const backendImagePattern = backendOrigin
  ? new URL(`${backendOrigin}/uploads/**`)
  : null;

const nextConfig: NextConfig = {
  images: {
    remotePatterns: [
      new URL("http://localhost:8082/uploads/**"),
      new URL("http://127.0.0.1:8082/uploads/**"),
      ...(backendImagePattern ? [backendImagePattern] : []),
    ],
  },
};

export default nextConfig;
