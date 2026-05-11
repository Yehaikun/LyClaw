#!/bin/bash

# 检测 8080-8086 端口是否有监听，如有则杀死对应进程

echo "========================================"
echo "  检测并清理 8080-8086 端口进程"
echo "========================================"

for port in {8080..8086}; do
    # 查找占用端口的 PID（兼容 Linux 和 macOS）
    if command -v lsof &> /dev/null; then
        # 使用 lsof（Linux/macOS 通用）
        pid=$(lsof -ti :$port 2>/dev/null)
    else
        # 使用 netstat + 正则（Linux）
        pid=$(netstat -tlnp 2>/dev/null | grep ":$port " | awk '{print $7}' | cut -d'/' -f1)
    fi
    
    if [ ! -z "$pid" ]; then
        echo "🔍 端口 $port 被进程 PID=$pid 占用"
        
        # 显示进程信息
        if command -v ps &> /dev/null; then
            ps_info=$(ps -p $pid -o comm= 2>/dev/null)
            echo "   进程名: $ps_info"
        fi
        
        # 杀死进程
        echo "   🔪 正在杀死进程 $pid ..."
        kill -9 $pid 2>/dev/null
        
        # 验证是否成功
        sleep 0.5
        if lsof -ti :$port 2>/dev/null; then
            echo "   ❌ 端口 $port 仍然被占用"
        else
            echo "   ✅ 端口 $port 已释放"
        fi
    else
        echo "✅ 端口 $port 空闲"
    fi
done

echo ""
echo "========================================"
echo "           清理完成"
echo "========================================"
