# NGINX edge for the e-commerce platform, with the OWASP Core Rule Set on ModSecurity as its web application firewall.
# Build: docker build -t e-commerce/edge edge/
# The base image brings NGINX, the ModSecurity module and the rule set, and renders the rule settings from the
# environment at start (PARANOIA, BLOCKING_PARANOIA, ANOMALY_INBOUND, MODSEC_RULE_ENGINE, …). Its own server and
# logging templates are replaced by ours; its ModSecurity templates are kept.
FROM owasp/modsecurity-crs:4.30.0-nginx-alpine-202611190211
USER root
# NGINX's OpenTelemetry module, from nginx.org's repository at exactly the base image's NGINX version.
RUN apk add --no-cache --repository https://nginx.org/packages/alpine/v3.24/main "nginx-module-otel=${NGINX_VERSION}.0.1.2-r1" \
 && rm -f /etc/nginx/templates/nginx.conf.template /etc/nginx/templates/conf.d/default.conf.template \
          /etc/nginx/templates/conf.d/logging.conf.template \
 && mkdir -p /etc/nginx/certs /usr/share/nginx/docs \
 && chown nginx /etc/nginx/certs
COPY nginx.conf security-headers.conf storefront-proxy.conf /etc/nginx/
COPY templates/default.conf.template /etc/nginx/templates/conf.d/default.conf.template
COPY waf/before-crs.conf /etc/modsecurity.d/owasp-crs/plugins/shop-before.conf
COPY 05-dev-certificate.sh /docker-entrypoint.d/
USER nginx
ENV GATEWAY_UPSTREAM=api-gateway:8080 \
    STOREFRONT_UPSTREAM=storefront:8090 \
    SIGN_IN_RATE=10r/m \
    ADDRESS_RATE=50r/s \
    OTEL_COLLECTOR=alloy:4317 \
    MODSEC_RULE_ENGINE=On \
    BLOCKING_PARANOIA=1 \
    DETECTION_PARANOIA=2 \
    ANOMALY_INBOUND=5 \
    ANOMALY_OUTBOUND=4 \
    MODSEC_RESP_BODY_ACCESS=Off \
    MODSEC_AUDIT_ENGINE=RelevantOnly \
    MODSEC_AUDIT_LOG=/dev/stdout \
    ALLOWED_METHODS="GET HEAD POST PUT PATCH DELETE OPTIONS"
EXPOSE 8080 8443
