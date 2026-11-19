# NGINX edge for the e-commerce platform. Build: docker build -t e-commerce/edge edge/
FROM nginx:1.29-alpine
RUN apk add --no-cache openssl \
 && mkdir -p /etc/nginx/certs /usr/share/nginx/docs \
 && rm /etc/nginx/conf.d/default.conf
COPY nginx.conf security-headers.conf storefront-proxy.conf /etc/nginx/
COPY templates/ /etc/nginx/templates/
COPY 05-dev-certificate.sh /docker-entrypoint.d/
ENV GATEWAY_UPSTREAM=api-gateway:8080 \
    STOREFRONT_UPSTREAM=storefront:8090 \
    SIGN_IN_RATE=10r/m \
    ADDRESS_RATE=50r/s
EXPOSE 8080 8443
