# Multi-stage Dockerfile for YuddhaSphere Authoritative RTS Server
FROM node:20-alpine AS builder

WORKDIR /app

# Copy shared-rules and server
COPY shared-rules ./shared-rules
COPY server ./server

# Build shared rules first
WORKDIR /app/shared-rules
RUN npm install
RUN npm run build

# Build server
WORKDIR /app/server
RUN npm install
RUN npm run build

# Production image
FROM node:20-alpine AS runner
WORKDIR /app

COPY --from=builder /app/shared-rules /app/shared-rules
COPY --from=builder /app/server/package.json ./package.json
COPY --from=builder /app/server/node_modules ./node_modules
COPY --from=builder /app/server/dist ./dist

ENV PORT=8080
EXPOSE 8080

CMD ["node", "dist/server.js"]
