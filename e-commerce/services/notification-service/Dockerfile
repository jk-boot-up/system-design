# syntax=docker/dockerfile:1.7
# Build: docker build -t e-commerce/notification-service .
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY cmd cmd
COPY internal internal
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /out/notification-service ./cmd/notification-service

# A static binary on distroless: no shell, no package manager, runs as non-root.
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/notification-service /notification-service
EXPOSE 8088
ENTRYPOINT ["/notification-service"]
