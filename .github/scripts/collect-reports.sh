#!/usr/bin/env bash
# 把 verify 产出的测试报告与覆盖率汇总到一个目录（默认 site/），供 GitHub Pages 发布到 reports/<ref>/
#   tests/surefire.html   单元测试（surefire，全部模块汇总）
#   tests/failsafe.html   集成测试（failsafe，全部模块汇总）
#   coverage/<模块>/       JaCoCo 覆盖率：每个模块一份；mall-app-aggregate 是启动模块测试覆盖到的全部业务模块
#   index.html            入口页
# 用法：bash .github/scripts/collect-reports.sh [输出目录] [标题]
set -uo pipefail

out="${1:-site}"
title="${2:-mall-evolution}"
rm -rf "$out"
mkdir -p "$out/tests" "$out/coverage"

# surefire-report 插件把汇总报告写在根模块 target/reports（带 css / images）
if [ -d target/reports ]; then
    cp -r target/reports/. "$out/tests/"
fi

modules=()
for dir in */target/site/jacoco; do
    [ -f "$dir/index.html" ] || continue
    module="${dir%%/*}"
    cp -r "$dir" "$out/coverage/$module"
    modules+=("$module")
done
for dir in */target/site/jacoco-aggregate; do
    [ -f "$dir/index.html" ] || continue
    module="${dir%%/*}-aggregate"
    cp -r "$dir" "$out/coverage/$module"
    modules+=("$module")
done

summary() {
    # 从 surefire XML 统计：用例数 失败 错误 跳过
    local pattern="$1"
    local tests=0 failures=0 errors=0 skipped=0
    for f in $pattern; do
        [ -f "$f" ] || continue
        line=$(grep -m1 -o '<testsuite [^>]*>' "$f") || continue
        for k in tests failures errors skipped; do
            v=$(echo "$line" | grep -o " $k=\"[0-9]*\"" | grep -o '[0-9]*')
            v=${v:-0}
            eval "$k=\$(( $k + v ))"
        done
    done
    echo "用例 $tests，失败 $failures，错误 $errors，跳过 $skipped"
}

unit=$(summary "*/target/surefire-reports/TEST-*.xml")
it=$(summary "*/target/failsafe-reports/TEST-*.xml")

{
    echo '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">'
    echo '<meta name="viewport" content="width=device-width, initial-scale=1">'
    echo "<title>${title} 测试报告</title>"
    echo '<style>body{font-family:system-ui,sans-serif;max-width:760px;margin:2rem auto;padding:0 1rem;line-height:1.6}'
    echo 'code{background:#f3f4f6;padding:0 .3em;border-radius:3px}</style></head><body>'
    echo "<h1>${title} 测试报告</h1>"
    echo "<p>提交 <code>${GITHUB_SHA:-local}</code>，生成于 $(date -u '+%Y-%m-%d %H:%M UTC')</p>"
    echo '<h2>测试</h2><ul>'
    if [ -f "$out/tests/surefire.html" ]; then
        echo "<li><a href=\"tests/surefire.html\">单元测试（surefire）</a>：${unit}</li>"
    fi
    if [ -f "$out/tests/failsafe.html" ]; then
        echo "<li><a href=\"tests/failsafe.html\">集成测试（failsafe）</a>：${it}</li>"
    fi
    echo '</ul><h2>覆盖率（JaCoCo，按模块）</h2><ul>'
    for m in "${modules[@]}"; do
        echo "<li><a href=\"coverage/${m}/index.html\">${m}</a></li>"
    done
    echo '</ul></body></html>'
} > "$out/index.html"

echo "报告已汇总到 $out：单元测试 ${unit}；集成测试 ${it}；覆盖率 ${#modules[@]} 个模块"
