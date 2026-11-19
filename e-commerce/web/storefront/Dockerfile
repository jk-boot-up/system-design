# Storefront image (design/16 §16.12): build with the full toolchain, run on distroless Node 26 as a non-root user with
# only production dependencies, no shell and no package manager. Lifecycle scripts never run (--ignore-scripts).
FROM node:26.7.0-alpine AS build
WORKDIR /app
COPY package.json package-lock.json .npmrc ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY . .
RUN npm run build \
 && npm ci --ignore-scripts --omit=dev --no-audit --no-fund \
 && mkdir -p /out/build \
 && mv build/client build/server /out/build/ \
 && find /out/build/client -name '*.map' -delete

FROM gcr.io/distroless/nodejs26-debian13:nonroot
WORKDIR /app
ENV NODE_ENV=production PORT=8090
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/package.json /app/server.ts /app/tracing.ts ./
COPY --from=build /out/build ./build
USER nonroot
EXPOSE 8090
CMD ["--disable-warning=ExperimentalWarning", "server.ts"]
