import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { LoginData, LoginResult, VerifyCodeResult, TenantInfo } from './types';
import { UserInfo } from '@/api/system/user/types';

// pc端固定客户端授权id
const clientId = import.meta.env.VITE_APP_CLIENT_ID;

/**
 * @param data {LoginData}
 * @returns
 */
export function login(data: LoginData): AxiosPromise<LoginResult> {
  const params = {
    ...data,
    clientId: data.clientId || clientId,
    grantType: data.grantType || 'password'
  };
  return request({
    url: '/auth/login',
    headers: {
      isToken: false,
      isEncrypt: true,
      repeatSubmit: false
    },
    method: 'post',
    data: params
  });
}

// 注册方法
export function register(data: any) {
  const params = {
    ...data,
    clientId: clientId,
    grantType: 'password'
  };
  return request({
    url: '/auth/register',
    headers: {
      isToken: false,
      isEncrypt: true,
      repeatSubmit: false
    },
    method: 'post',
    data: params
  });
}

/**
 * 注销
 */
export function logout() {
  if (import.meta.env.VITE_APP_SSE === 'true') {
    request({
      url: '/resource/sse/close',
      method: 'get'
    });
  }
  return request({
    url: '/auth/logout',
    method: 'post'
  });
}

/**
 * 获取验证码
 */
export function getCodeImg(): AxiosPromise<VerifyCodeResult> {
  return request({
    url: '/auth/code',
    headers: {
      isToken: false
    },
    method: 'get',
    timeout: 20000
  });
}

/**
 * 第三方登录
 */
export function callback(data: LoginData): AxiosPromise<any> {
  const LoginData = {
    ...data,
    clientId: clientId,
    grantType: 'social'
  };
  return request({
    url: '/auth/social/callback',
    method: 'post',
    data: LoginData
  });
}

// 获取用户详细信息
export function getInfo(): AxiosPromise<UserInfo> {
  return request({
    url: '/system/user/getInfo',
    method: 'get'
  });
}

// 获取租户列表
// 租户列表是公开数据，后端按 IP 限流（同一工厂出口的多人共享额度）：同一会话内缓存 5 分钟，避免刷新页面就被限流
const TENANT_CACHE = 'ls-tenant-list';
export function getTenantList(isToken: boolean): AxiosPromise<TenantInfo> {
  try {
    const hit = JSON.parse(sessionStorage.getItem(TENANT_CACHE) ?? 'null');
    if (hit && Date.now() - hit.at < 5 * 60_000) return Promise.resolve(hit.res);
  } catch {
    /* 缓存不可用时直接请求 */
  }
  return (
    request({
      url: '/auth/tenant/list',
      headers: {
        isToken: isToken
      },
      method: 'get'
    }) as AxiosPromise<TenantInfo>
  ).then((res: any) => {
    try {
      sessionStorage.setItem(TENANT_CACHE, JSON.stringify({ at: Date.now(), res: { code: res.code, msg: res.msg, data: res.data } }));
    } catch {
      /* 忽略 */
    }
    return res;
  });
}
