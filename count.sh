# 汇总所有 lyclaw-* 目录的统计
echo "========================================"
echo "       Java 文件统计汇总"
echo "========================================"

total_files=0
total_lines=0

for dir in lyclaw-*/; do
    # 统计当前目录（递归子目录）下的 .java 文件数量和行数
    file_count=$(find "$dir" -name "*.java" -type f | wc -l)
    line_count=$(find "$dir" -name "*.java" -type f -exec cat {} + | wc -l)

    echo ""
    echo "📁 $dir"
    echo "   文件数: $file_count"
    echo "   行数:   $line_count"

    total_files=$((total_files + file_count))
    total_lines=$((total_lines + line_count))
done

echo ""
echo "========================================"
echo "  📊 汇总统计"
echo "    总文件数: $total_files"
echo "    总行数:   $total_lines"
echo "========================================"
