import { authStore, request, type Tokens } from './client'
export type User = { id: number; nickname: string; email: string }
export const signUp = (nickname: string, email: string, password: string) =>
  request<User>('/api/users', 'POST', { nickname, email, password }, false)
export async function login(email: string, password: string) {
  const tokens = await request<Tokens>('/api/auth/login', 'POST', { email, password }, false)
  authStore.set(tokens)
  return tokens
}
export const getMe = () => request<User>('/api/users/me')
export async function logout() {
  const tokens = authStore.get()
  if (tokens) await request<void>('/api/auth/logout', 'POST', { refreshToken: tokens.refreshToken })
  authStore.clear()
}
