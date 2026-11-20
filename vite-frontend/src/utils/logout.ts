/**
 * 安全退出登录函数
 * 清除登录相关数据，但保留用户偏好设置（如主题）
 */
import { clearSession } from './auth';

export const safeLogout = () => {
  clearSession();
  localStorage.removeItem('lastNotified');
  localStorage.removeItem('forward-order');
  localStorage.removeItem('e');
};
