import request from '@/utils/request'

export function getSeatList(data) {
    return request({
        url: '/damai/program/seat/relate/info/v2',
        method: 'post',
        data:data

    })
}

