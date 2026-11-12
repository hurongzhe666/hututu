package cn.varin.hututu.manager.upload;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.varin.hututu.config.COSClientConfig;
import cn.varin.hututu.manager.CosManager;
import cn.varin.hututu.model.dto.file.UploadPictureResult;
import com.qcloud.cos.model.PutObjectResult;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.io.File;
import java.util.Date;

/**
 * 文件上传的模版方式：分为了：使用文件上传和使用url上传
 *
 * 公共流程：
 * 校验文件
 * 获取上传地址
 * 获取本地临时文件
 * 上传到对象存储
 * 封装解析得到的图片信息
 * 清理临时文件
 * 需要单独处理的：
 * 校验图片
 * 获取文件名称
 * 保存临时文件
 */

@Slf4j
public abstract class PictureUploadTemplate {

    @Resource
    private COSClientConfig cosClientConfig;
    @Resource
    private CosManager cosManager;

    // 验证图片
    abstract void validPicture(Object fileResource);

    // 获取文件名称
    abstract String getFileName(Object fileResource);

    // 保存临时文件
    abstract void getTempFile(Object fileResource, File file) throws Exception;

    /**
     * 上传图片并且返回图片属性信息
     * @param fileResource  文件
     * @param uploadPicturePathPrefix 文件上传路径前缀
     * @return 成功返回的图片信息
     */
    public UploadPictureResult uploadPicture(Object fileResource, String uploadPicturePathPrefix) {
        // 1. 验证图片
        validPicture(fileResource);
        // 2. 拼接图片路径：16位UUID_当前日期.图片后缀
        String uuid = RandomUtil.randomString(16);
        String originFilename = getFileName(fileResource);

        String uploadFilename = String.format("%s_%s.%s", DateUtil.formatDate(new Date()), uuid,
                FileUtil.getSuffix(originFilename));
        String uploadPath = String.format("%s/%s", uploadPicturePathPrefix, uploadFilename);

        File tempFile = null;
        try {
            // 3. 上传图片
            tempFile = File.createTempFile(uploadPath, null);
            getTempFile(fileResource, tempFile);
            PutObjectResult putObjectResult = cosManager.putObject(uploadPath, tempFile);

            // ========== 删除CI数据万象读取逻辑！不再调用图片处理，不会报role不存在 ==========
            // 直接用原图信息封装，宽高先写0，缩略图=原图地址
            return buildResultNoCi(originFilename, tempFile, uploadPath);

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            this.deleteFile(tempFile);
        }
    }

    /**
     * 清除文件
     */
    public void deleteFile(File file) {
        if (file == null) {
            return;
        }
        boolean delete = file.delete();
        if (!delete) {
            log.error("file delete error :{}", file.getAbsolutePath());
        }
    }

    /**
     * 【新方法】不依赖数据万象CI，直接封装结果
     */
    private UploadPictureResult buildResultNoCi(String originFilename, File file, String uploadPath) {
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        // 宽高拿不到，先填0，页面不崩溃
        int picWidth = 0;
        int picHeight = 0;
        double picScale = 0.0;
        uploadPictureResult.setPicName(FileUtil.mainName(originFilename));
        uploadPictureResult.setPicWidth(picWidth);
        uploadPictureResult.setPicHeight(picHeight);
        uploadPictureResult.setPicScale(picScale);
        uploadPictureResult.setPicFormat(FileUtil.getSuffix(originFilename));
        uploadPictureResult.setPicSize(FileUtil.size(file));
        // 保证 host 带协议前缀，避免生成的图片 url 是相对路径导致前端无法显示
        String host = cosClientConfig.getHost();
        if (host != null && !host.startsWith("http://") && !host.startsWith("https://")) {
            host = "https://" + host;
        }
        String fullUrl = host + "/" + uploadPath;
        uploadPictureResult.setUrl(fullUrl);
        uploadPictureResult.setThumbnailUrl(fullUrl); // 缩略图等于原图
        uploadPictureResult.setOriginUrl(fullUrl);
        return uploadPictureResult;
    }

    // ========== 下面两个旧的buildResult方法，先注释保留，以后要CI的时候再取消注释 ==========
    /*
    private UploadPictureResult buildResult(String originFilename, File file, String uploadPath, ImageInfo imageInfo) {
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        int picWidth = imageInfo.getWidth();
        int picHeight = imageInfo.getHeight();
        double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
        uploadPictureResult.setPicName(FileUtil.mainName(originFilename));
        uploadPictureResult.setPicWidth(picWidth);
        uploadPictureResult.setPicHeight(picHeight);
        uploadPictureResult.setPicScale(picScale);
        uploadPictureResult.setPicFormat(imageInfo.getFormat());
        uploadPictureResult.setPicSize(FileUtil.size(file));
        uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + uploadPath);
        return uploadPictureResult;
    }

    private UploadPictureResult buildResult(String originFilename, String uploadPath,CIObject ...  ciObject ) {
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        CIObject webpciObject = ciObject[0];
        CIObject thumbciObject = ciObject[1];
        int picWidth = webpciObject.getWidth();
        int picHeight = webpciObject.getHeight();
        double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
        uploadPictureResult.setPicName(FileUtil.mainName(originFilename));
        uploadPictureResult.setPicWidth(picWidth);
        uploadPictureResult.setPicHeight(picHeight);
        uploadPictureResult.setPicScale(picScale);
        uploadPictureResult.setPicFormat(webpciObject.getFormat());
        long l = webpciObject.getSize().longValue();
        uploadPictureResult.setPicSize(l);
        uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + webpciObject.getKey());
        uploadPictureResult.setOriginUrl(cosClientConfig.getHost() + "/" + uploadPath);
        uploadPictureResult.setThumbnailUrl(cosClientConfig.getHost() + "/" + thumbciObject.getKey());
        return uploadPictureResult;
    }
    */
}

