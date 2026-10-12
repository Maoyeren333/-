import{createApp}from'vue';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import 'element-plus/dist/index.css';
import './style.css';
import './theme-enhancements.css';
import App from './App.vue';
import router from './router';
// 显式使用中文语言包，否则确认框、日期选择等组件会显示英文（Cancel / OK）
createApp(App).use(router).use(ElementPlus,{locale:zhCn}).mount('#app');
