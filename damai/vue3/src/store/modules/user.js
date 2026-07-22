import { login, logout, smsLogin } from '@/api/login'
import { getToken, setToken, removeToken, getName, setName, removeName,
    getUserIdKey, setUserIdKey, removeUserIdKey } from '@/utils/auth'
import { defineStore } from 'pinia'

const useUserStore = defineStore(
    'user',
    {
        state: () => ({
            token: getToken(),
            name: getName(),
            avatar: '',
            userId: getUserIdKey(),
            roles: [],
            permissions: []
        }),
        actions: {
            login(userInfo) {
                const email = userInfo.email
                const mobile = userInfo.mobile
                const password = userInfo.password
                const code = userInfo.code
                return new Promise((resolve, reject) => {
                    login(email, mobile, password, code).then(res => {
                        if(res.code == 0){
                            setToken(res.data.token)
                            userInfo.mobile? setName(userInfo.mobile) : setName(userInfo.email)
                            setUserIdKey(res.data.userId)
                            this.token = res.data.token
                            this.userId = res.data.userId
                            resolve()
                        } else {
                            ElMessage.error(res.message)
                        }
                    }).catch(error => {
                        reject(error)
                    })
                })
            },
            /** 短信登录 */
            smsLogin(userInfo) {
                const mobile = userInfo.mobile
                const smsCode = userInfo.smsCode
                const code = userInfo.code
                return new Promise((resolve, reject) => {
                    smsLogin(mobile, smsCode, code).then(res => {
                        if(res.code == 0){
                            setToken(res.data.token)
                            setName(userInfo.mobile)
                            setUserIdKey(res.data.userId)
                            this.token = res.data.token
                            this.userId = res.data.userId
                            resolve()
                        } else {
                            ElMessage.error(res.message)
                        }
                    }).catch(error => {
                        reject(error)
                    })
                })
            },
            logOut() {
                return new Promise((resolve, reject) => {
                    logout('0001', this.token).then(() => {
                        this.token = ''
                        this.roles = []
                        this.permissions = []
                        removeToken()
                        resolve()
                    }).catch(error => {
                        reject(error)
                    })
                })
            }
        }
    })

export default useUserStore