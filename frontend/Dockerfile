# Frontend image for Cloud Run (or any container runtime): static build served by nginx, which also proxies
# the game WebSocket (/ws) to the backend. The browser only ever talks to this origin.
#
# Build:  docker build -t arrow-chase-web frontend
# Run:    docker run -p 8081:8080 -e BACKEND_URL=http://host.docker.internal:8080 arrow-chase-web

FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:stable-alpine
# The nginx image runs envsubst over /etc/nginx/templates/*.template at startup, substituting only variables
# that are set in the environment (PORT, BACKEND_URL), so nginx's own $variables are left alone.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
ENV PORT=8080 \
    BACKEND_URL=http://backend:8080
EXPOSE 8080
