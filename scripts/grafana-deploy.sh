#!/bin/bash
# Grafana 一键恢复：推送 dashboard + 验证
# 用法: ./scripts/grafana-deploy.sh

set -e
DASHBOARD="docker/grafana-dashboards/cloud-ops-dashboard.json"
GRAFANA_URL="http://localhost:3001"

echo "==> 检查 Grafana 是否运行..."
if ! curl -sf "$GRAFANA_URL/api/health" > /dev/null 2>&1; then
    echo "ERROR: Grafana 未运行 ($GRAFANA_URL)"
    echo "请先启动: cd docker && docker compose -f docker-compose-monitoring.yml up -d"
    exit 1
fi

echo "==> 推送 Dashboard..."
RESULT=$(curl -s -X POST "$GRAFANA_URL/api/dashboards/db" \
    -H "Content-Type: application/json" \
    -d @"$DASHBOARD")

STATUS=$(echo "$RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))")
URL=$(echo "$RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('url','?'))")

if [ "$STATUS" = "success" ]; then
    echo "OK: Dashboard 已部署 -> $GRAFANA_URL$URL"
else
    echo "FAIL: $RESULT"
    exit 1
fi
