import request from '@/utils/request'

export function login(email, mobile, password, code) {
    const data = { email, mobile, password, code }
    return request({
        url: '/damai/user/user/login',
        method: 'post',
        data: data
    })
}

export function logout(code,token) {
    const data = { token, code }
    return request({
        url: '/damai/user/user/logout',
        method: 'post',
        data:data
    })
}

export function isCaptcha(){
    return request({
        url: '/damai/user/user/captcha/check/need',
        method: 'post'
    })
}

export function register(data){
    return request({
        url: '/damai/user/user/register',
        method: 'post',
        data:data
    })
}

/** 短信登录：发送验证码 */
export function sendSmsCode(mobile) {
    return request({
        url: '/damai/user/user/sms/send',
        method: 'post',
        data: { mobile }
    })
}

/** 短信登录：验证码登录 */
export function smsLogin(mobile, smsCode, code) {
    return request({
        url: '/damai/user/user/sms/login',
        method: 'post',
        data: { mobile, smsCode, code }
    })
}