FROM news-aggregator:local

ARG COMMIT_SHA=local

COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter:1.0.1 \
     /lambda-adapter /opt/extensions/lambda-adapter

ENV PORT=8080 \
    AWS_LWA_READINESS_CHECK_PATH=/api/health \
    NEWS_COMMIT_SHA=${COMMIT_SHA}

ENTRYPOINT ["/cnb/process/web"]