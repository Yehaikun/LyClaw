#!/bin/bash

# 汇总所有 lyclaw-* 目录的统计
echo "========================================"
echo "       Java 文件统计汇总"
echo "========================================"

total_files=0
total_lines=0
total_imports=0
total_test_files=0
total_test_lines=0

for dir in lyclaw-*/; do
    # 统计主代码（排除测试目录和测试文件）
    main_files=$(find "$dir" -name "*.java" -type f \
        ! -path "*/test/*" \
        ! -name "*Test.java" \
        ! -name "*Tests.java" \
        ! -name "*IT.java" \
        2>/dev/null | wc -l)
    
    main_lines=$(find "$dir" -name "*.java" -type f \
        ! -path "*/test/*" \
        ! -name "*Test.java" \
        ! -name "*Tests.java" \
        ! -name "*IT.java" \
        -exec cat {} + 2>/dev/null | wc -l)
    
    # 统计测试代码
    test_files=$(find "$dir" -name "*.java" -type f \
        \( -path "*/test/*" \
        -o -name "*Test.java" \
        -o -name "*Tests.java" \
        -o -name "*IT.java" \) \
        2>/dev/null | wc -l)
    
    test_lines=$(find "$dir" -name "*.java" -type f \
        \( -path "*/test/*" \
        -o -name "*Test.java" \
        -o -name "*Tests.java" \
        -o -name "*IT.java" \) \
        -exec cat {} + 2>/dev/null | wc -l)
    
    # 统计 import 语句数量（排除测试目录）
    import_count=$(find "$dir" -name "*.java" -type f \
        ! -path "*/test/*" \
        ! -name "*Test.java" \
        -exec grep -h "^import " {} \; 2>/dev/null | sort -u | wc -l)
    
    echo ""
    echo "📁 $dir"
    echo "   📝 主代码:   文件数=$main_files, 行数=$main_lines"
    echo "   🧪 测试代码: 文件数=$test_files, 行数=$test_lines"
    echo "   📦 唯一import: $import_count"
    
    total_files=$((total_files + main_files))
    total_lines=$((total_lines + main_lines))
    total_test_files=$((total_test_files + test_files))
    total_test_lines=$((total_test_lines + test_lines))
    total_imports=$((total_imports + import_count))
done

echo ""
echo "========================================"
echo "  📊 汇总统计"
echo "    主代码文件数: $total_files"
echo "    主代码行数:   $total_lines"
echo "    测试代码文件数: $total_test_files"
echo "    测试代码行数:   $total_test_lines"
echo "    唯一import语句数: $total_imports"
echo "========================================"

# 可选：导出到 CSV 文件
if [[ "$1" == "--export" ]]; then
    echo "目录,主代码文件数,主代码行数,测试文件数,测试行数,唯一import数" > java_stats.csv
    for dir in lyclaw-*/; do
        main_f=$(find "$dir" -name "*.java" -type f ! -path "*/test/*" ! -name "*Test.java" 2>/dev/null | wc -l)
        main_l=$(find "$dir" -name "*.java" -type f ! -path "*/test/*" ! -name "*Test.java" -exec cat {} + 2>/dev/null | wc -l)
        test_f=$(find "$dir" -name "*.java" -type f \( -path "*/test/*" -o -name "*Test.java" \) 2>/dev/null | wc -l)
        test_l=$(find "$dir" -name "*.java" -type f \( -path "*/test/*" -o -name "*Test.java" \) -exec cat {} + 2>/dev/null | wc -l)
        imp_c=$(find "$dir" -name "*.java" -type f ! -path "*/test/*" -exec grep -h "^import " {} \; 2>/dev/null | sort -u | wc -l)
        echo "${dir%,},$main_f,$main_l,$test_f,$test_l,$imp_c" >> java_stats.csv
    done
    echo "已导出到 java_stats.csv"
fi
