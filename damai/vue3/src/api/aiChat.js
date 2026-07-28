import { getUserIdKey } from '@/utils/auth'
import request from '@/utils/request'

/**
 * 鍙戦€佹秷鎭粰AI瀹㈡湇(鏅€?
 */
export function sendAiMessage(message, sessionId) {
    return request({
        url: '/damai/ai/chat/send',
        method: 'post',
        data: { message, sessionId: sessionId || '' }
    })
}

/**
 * 鍙戦€佹秷鎭粰AI瀹㈡湇(娴佸紡)
 * @param {string} message
 * @param {string} sessionId
 * @param {AbortSignal} signal - 鐢ㄤ簬涓柇璇锋眰
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
    if (!response.ok) throw new Error('璇锋眰澶辫触')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop()
        let eventData = []
        for (const line of lines) {
            if (line.startsWith('data:')) {
                eventData.push(line.slice(5))
            } else if (line === '' && eventData.length > 0) {
                yield eventData.join('\n')
                eventData = []
            }
        }
        if (eventData.length > 0 && buffer === '') {
            yield eventData.join('\n')
        }
    }
}

/**
 * 鑾峰彇浼氳瘽鍒楄〃
 * @returns {Promise<{code: number, data: Array<{id: string, title: string, updatedAt: number, messageCount: number}>}>}
 */
export function getConversations() {
    return request({
        url: '/damai/ai/conversations',
        method: 'get'
    })
}

/**
 * 鑾峰彇鏌愪釜浼氳瘽鐨勫畬鏁存秷鎭? * @param {string} sessionId
 * @returns {Promise<{code: number, data: {id: string, messages: Array<{role: string, content: string, time: string}>}}>}
 */
export function getConversationMessages(sessionId) {
    return request({
        url: '/damai/ai/conversations/' + sessionId,
        method: 'get'
    })
}

/**
 * 鍒犻櫎鏌愪釜浼氳瘽
 * @param {string} sessionId
 * @returns {Promise<{code: number}>}
 */
export function deleteConversation(sessionId) {
    return request({
        url: '/damai/ai/conversations/' + sessionId,
        method: 'delete'
    })
}
/**
 * 提交消息满意度反馈
 * @param {number} messageId
 * @param {number} satisfaction - 1=赞 0=踩
 */
export function submitSatisfaction(messageId, satisfaction) {
    return request({
        url: '/damai/ai/message/' + messageId + '/satisfaction',
        method: 'put',
        data: { satisfaction }
    })
}