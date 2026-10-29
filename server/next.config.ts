import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  // A self-contained server in .next/standalone, copied into the image.
  output: 'standalone',
};

export default nextConfig;
