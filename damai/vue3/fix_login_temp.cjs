const fs = require('fs');
let c = fs.readFileSync('E:/codexop/damai/vue3/src/views/login.vue', 'utf8');
const marker = 'let smsCountdownTimer = null';
const insert = '\n' +
const smsLoginRef = ref(null)

const handleSendSmsCode = () => {
  const mobile = smsLoginForm.value.mobile;
  if (!mobile || mobile.length !== 11) {
    ElMessage.warning('请输入正确的手机号');
    return;
  }
  sendSmsCode(mobile).then(() => {
    ElMessage.success('验证码已发送（控制台查看）');
    smsCodeBtnDisabled.value = true;
    let countdown = 60;
    smsCodeBtnText.value = countdown + 's';
    if (smsCountdownTimer) clearInterval(smsCountdownTimer);
    smsCountdownTimer = setInterval(() => {
      countdown--;
      smsCodeBtnText.value = countdown + 's';
      if (countdown <= 0) {
        clearInterval(smsCountdownTimer);
        smsCountdownTimer = null;
        smsCodeBtnDisabled.value = false;
        smsCodeBtnText.value = '获取验证码';
      }
    }, 1000);
  }).catch(() => {
    ElMessage.error('验证码发送失败');
  });
};

const handleSmsLogin = () => {
  smsLoginRef.value.validate((valid) => {
    if (!valid) return;
    smsLoading.value = true;
    const { mobile, smsCode, code } = smsLoginForm.value;
    smsLogin(mobile, smsCode, code).then(() => {
      ElMessage.success('登录成功');
      router.push({ path: '/' });
    }).catch(() => {
      smsLoading.value = false;
    });
  });
};

onBeforeUnmount(() => {
  if (smsCountdownTimer) clearInterval(smsCountdownTimer);
});
;
c = c.replace(marker, marker + insert);
fs.writeFileSync('E:/codexop/damai/vue3/src/views/login.vue', c, 'utf8');
console.log('Done');
