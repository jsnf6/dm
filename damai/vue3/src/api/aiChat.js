import { getUserIdKey } from '@/utils/auth'
import request from '@/utils/request'

/**
 * 发送消息给AI客服(普通)
 */
export function sendAiMessage(message, sessionId) {
    return request({
        url: '/damai/ai/chat/send',
        method: 'post',
        data: { message, sessionId: sessionId || '' }
    })
}

/**
 * 发送消息给AI客服(流式)
 * @param {string} message
 * @param {string} sessionId
 * @param {AbortSignal} signal - 用于中断请求
 */
export async function* sendAiMessageStream(message, sessionId, signal) {
    const response = await fetch('/barley-dev/damai/ai/chat/stream', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-User-Id': getUserIdKey() || ''
        },
        body: JSON.stringify({ message, sessionId: sessionId || '' }),
        signal
    })
    if (!response.ok) throw new Error('请求失败')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop()
        for (const line of lines) {
            if (line.startsWith('data:')) {
                yield line.slice(5).trim()
            }
        }
    }
}

/**
 * 获取会话列表
 * @returns {Promise<{code: number, data: Array<{id: string, title: string, updatedAt: number, messageCount: number}>}>}
 */
export function getConversations() {
    return request({
        url: '/damai/ai/conversations',
        method: 'get'
    })
}

/**
 * 获取某个会话的完整消息
 * @param {string} sessionId
 * @returns {Promise<{code: number, data: {id: string, messages: Array<{role: string, content: string, time: string}>}}>}
 */
export function getConversationMessages(sessionId) {
    return request({
        url: '/damai/ai/conversations/' + sessionId,
        method: 'get'
    })
}

/**
 * 删除某个会话
 * @param {string} sessionId
 * @returns {Promise<{code: number}>}
 */
export function deleteConversation(sessionId) {
    return request({
        url: '/damai/ai/conversations/' + sessionId,
        method: 'delete'
    })
}