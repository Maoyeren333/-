import axios from 'axios'
const http=axios.create({baseURL:'/api',timeout:120000,withCredentials:true})
http.interceptors.response.use(r=>r.data,e=>Promise.reject(e))
export const taskApi={list:(params={})=>http.get('/tasks',{params}),page:(params={})=>http.get('/tasks/page',{params}),get:id=>http.get(`/tasks/${id}`),detail:id=>http.get(`/tasks/${id}/detail`),create:d=>http.post('/tasks',d),remove:id=>http.delete(`/tasks/${id}`),run:(id,algorithm,extra={})=>http.post(`/tasks/${id}/interpretations`,{algorithm,...extra})}
export const imageApi={list:taskId=>http.get(`/tasks/${taskId}/images`),upload:(taskId,file,onUploadProgress)=>{const f=new FormData();f.append('file',file);return http.post(`/tasks/${taskId}/images`,f,{onUploadProgress})},remove:(taskId,imageId)=>http.delete(`/tasks/${taskId}/images/${imageId}`)}
export const interpretationApi={list:(params={})=>http.get('/interpretations',{params}),page:(params={})=>http.get('/interpretations/page',{params}),get:id=>http.get(`/interpretations/${id}`),remove:id=>http.delete(`/interpretations/${id}`),previewUrl:id=>`/api/interpretations/${id}/preview`,maskUrl:id=>`/api/interpretations/${id}/mask`}
export const exportApi={list:()=>http.get('/exports'),packageUrl:id=>`/api/exports/${id}/package`,packageAllUrl:()=>'/api/exports/package'}
export const notificationApi={list:()=>http.get('/notifications')}
export const algorithmApi={list:()=>http.get('/algorithms'),health:()=>http.get('/algorithms/health')}
export const authApi={register:data=>http.post('/auth/register',data),login:data=>http.post('/auth/login',data),me:()=>http.get('/auth/me'),logout:()=>http.post('/auth/logout'),updateProfile:data=>http.put('/auth/profile',data),changePassword:data=>http.put('/auth/password',data)}
export const monitorApi={overview:(latitude=31.2304,longitude=121.4737,locationName='上海作业区')=>http.get('/monitor/overview',{params:{latitude,longitude,locationName}})}
export const dashboardApi={summary:()=>http.get('/dashboard/summary'),trend:(days=7)=>http.get('/dashboard/trend',{params:{days}}),algorithms:()=>http.get('/dashboard/algorithms')}
export const commandApi={overview:()=>http.get('/command/overview')}
export default http
