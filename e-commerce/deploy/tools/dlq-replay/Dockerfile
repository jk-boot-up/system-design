# Build: docker build -t e-commerce/dlq-replay deploy/tools/dlq-replay
FROM python:3.14-slim
RUN useradd --system --uid 10001 app
COPY requirements.txt /app/
RUN pip install --no-cache-dir -r /app/requirements.txt
COPY dlq_replay.py /app/
USER 10001
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
ENTRYPOINT ["python", "/app/dlq_replay.py"]
