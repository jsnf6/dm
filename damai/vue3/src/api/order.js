import request from '@/utils/request'

export function orderCreateV1Api(data) {
    return request({
        url: '/damai/program/program/order/create/v1',
        method: 'post',
        data:data
    })
}

export function orderCreateV2Api(data) {
    return request({
        url: '/damai/program/program/order/create/v2',
        method: 'post',
        data:data
    })
}

export function orderCreateV3Api(data) {
    return request({
        url: '/damai/program/program/order/create/v3',
        method: 'post',
        data:data
    })
}

export function orderCreateV4Api(data) {
    return request({
        url: '/damai/program/program/order/create/v4',
        method: 'post',
        data:data
    })
}

export function getOrderListApi(data) {
    return request({
        url: '/damai/order/order/select/list/v2',
        method: 'post',
        data:data
    })
}

export function cancelOrderApi(data) {
    return request({
        url: '/damai/order/order/cancel',
        method: 'post',
        data:data
    })
}

export function getOrderDetailApi(data) {
    return request({
        url: '/damai/order/order/get/v2',
        method: 'post',
        data:data
    })
}

export function getOrderCacheApi(data) {
    return request({
        url: '/damai/order/order/get/cache',
        method: 'post',
        data:data
    })
}

/**
 * 璁㈠崟鏀粯
 * @param params
 * */
export function orderPayApi(params){
    return request({
        url: '/damai/order/order/pay',
        method: 'post',
        data: params
    })
}

/**
 * 妫€鏌ヨ鍗曟敮浠樼姸鎬?
 * */
export function payCheckApi(params){
    return request({
        url: '/damai/order/order/pay/check',
        method: 'post',
        data: params
    })
}