# syntax=docker/dockerfile:1.7
# Build: docker build -t e-commerce/recommendation-service .
FROM python:3.14-slim AS build
WORKDIR /src
RUN python -m venv /venv
COPY pyproject.toml ./
COPY src src
RUN --mount=type=cache,target=/root/.cache/pip /venv/bin/pip install .

FROM python:3.14-slim
RUN useradd --system --uid 10001 app
COPY --from=build /venv /venv
USER 10001
ENV PATH=/venv/bin:$PATH PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
EXPOSE 8089
ENTRYPOINT ["python", "-m", "recommendation.main"]
