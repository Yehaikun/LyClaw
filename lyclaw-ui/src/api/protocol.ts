/**
 * Protocol服务API封装，提供MCP工具发现、模型对话和A2A代理卡片获取等接口。
 *
 * LyClaw的Protocol服务承担协议网关角色，负责以下功能：
 * 1. MCP（Model Context Protocol）集成：发现和管理外部MCP服务器提供的工具
 * 2. A2A（Agent-to-Agent）协议：代理间通信与能力发现
 * 3. 模型对话代理：将内部请求转发至外部模型提供商（DeepSeek、Anthropic、OpenAI等）
 *
 * 本模块是前端与外部工具生态和模型提供商之间的桥梁。
 */
import { get, post } from './client'
import type { AgentCard, McpToolDescriptor } from '../types'

/**
 * 发现MCP服务器提供的工具列表。
 *
 * MCP（Model Context Protocol）是一种标准化的工具集成协议，
 * 允许外部服务器（如Brave Search、GitHub等）通过统一接口暴露工具。
 * 此函数通过执行指定的MCP服务器命令来获取该服务器提供的所有工具描述。
 *
 * @param serverCommand 用于启动MCP服务器的命令（如"npx @brave-search/mcp"）
 * @returns McpToolDescriptor数组，包含工具名称、描述和输入schema
 */
export function discoverMcpTools(
  serverCommand: string,
): Promise<McpToolDescriptor[]> {
  const params = new URLSearchParams({ serverCommand })
  return post<McpToolDescriptor[]>(
    `/api/protocol/mcp/discover?${params.toString()}`,
  )
}

/**
 * 通过协议网关向模型提供商发起聊天请求。
 *
 * 将内部的ChatRequest转换为模型提供商特定的API格式（如OpenAI格式、
 * Anthropic格式或DeepSeek格式），处理认证、请求转换和响应解析。
 * 这允许前端通过统一的API端点访问多个不同提供商的模型。
 *
 * @param body 包含消息历史、模型名称和生成参数的请求对象
 * @returns 模型返回的聊天响应对象
 */
export function modelChat(
  body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  return post<Record<string, unknown>>('/api/protocol/model/chat', body)
}

/**
 * 获取当前代理的A2A能力卡片。
 *
 * A2A（Agent-to-Agent）协议定义了代理间相互发现和通信的标准。
 * 代理卡片是一个自描述文档，包含代理的ID、名称、描述、版本、
 * 能力列表（TEXT_GEN、TOOL_USE、CODE_EXEC等）和服务端点。
 * 其他代理通过此卡片了解本代理的能力并决定是否委托任务。
 *
 * @returns AgentCard包含代理标识、能力和端点信息
 */
export function getAgentCard(): Promise<AgentCard> {
  return get<AgentCard>('/api/protocol/a2a/card')
}
