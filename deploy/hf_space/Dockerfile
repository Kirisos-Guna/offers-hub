# Offers Hub listener on a Hugging Face Docker Space (free CPU).
FROM python:3.12-slim

# Spaces run containers as user 1000.
RUN useradd -m -u 1000 user
WORKDIR /home/user/app

COPY --chown=user requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY --chown=user . .
USER user

ENV PYTHONUNBUFFERED=1 \
    PORT=7860 \
    KEEP_DAYS=7 \
    BACKFILL_LIMIT=100
EXPOSE 7860
CMD ["python", "listener.py"]
