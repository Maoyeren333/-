-- 任务地理坐标：用于实时监测页的态势点位真实定位
-- 已在 2026-10-12 应用到本机 uav_remote_sensing，全新初始化时 schema.sql 已包含这两列。
USE uav_remote_sensing;
ALTER TABLE uav_task ADD COLUMN latitude DOUBLE NULL;
ALTER TABLE uav_task ADD COLUMN longitude DOUBLE NULL;
