/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "standalone", // small runtime image for Docker
  reactStrictMode: true,
};

export default nextConfig;
