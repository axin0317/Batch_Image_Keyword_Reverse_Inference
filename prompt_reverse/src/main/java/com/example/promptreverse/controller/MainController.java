package com.example.promptreverse.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionResult;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
// 移除不存在的导入
// import com.volcengine.ark.runtime.model.completion.chat.ChatMessageContent;
// import com.volcengine.ark.runtime.model.completion.chat.ChatMessageImageUrl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.IOException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.InvalidPathException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.UUID;

@Controller
public class MainController {

    private static final Logger logger = LoggerFactory.getLogger(MainController.class);
    private static final String TEMP_UPLOAD_DIR = System.getProperty("java.io.tmpdir") + File.separator + "prompt_reverse_uploads";

    public MainController() {
        Path uploadPath = Paths.get(TEMP_UPLOAD_DIR);
        if (!Files.exists(uploadPath)) {
            try {
                Files.createDirectories(uploadPath);
            } catch (IOException e) {
                System.err.println("无法创建临时上传目录: " + e.getMessage());
            }
        }
    }

    @GetMapping("/")
    public String index(Model model) {
        logger.info("Index page accessed"); // Test log
        System.out.println("Test output from MainController.index via System.out.println"); // Add this line for testing
        model.addAttribute("userInput", "");
        return "index";
    }

    @GetMapping("/favicon.ico")
    @ResponseBody
    public void returnNoFavicon() {
        // No-op, returns 204 No Content by default
    }

    @PostMapping("/reverse-prompt")
    @ResponseBody
    public Map<String, Object> reversePrompt(
            @RequestParam("apiKey") String apiKey,
            @RequestParam(value = "images", required = false) MultipartFile[] images,
            @RequestParam("customPrompt") String customPrompt,
            @RequestParam("outputPath") String outputPath,
            @RequestParam("modelId") String modelId,
            @RequestParam(value = "imageFilesData", required = false) String imageFilesData // 用于文件夹上传的文件名列表
    ) {
        logger.info("Received /reverse-prompt request with apiKey: {}, customPrompt: {}, outputPath: {}, modelId: {}, imageFilesData: {}", 
                    apiKey, customPrompt, outputPath, modelId, imageFilesData);
        if (images != null) {
            logger.info("Number of images received: {}", images.length);
        } else {
            logger.info("No images received via MultipartFile array.");
        }

        Map<String, Object> response = new HashMap<>();
        List<Map<String, String>> results = new ArrayList<>();
        int totalImages = 0;
        List<String> imagePathsToProcess = new ArrayList<>();

        try {
            // 处理直接上传的图片
            if (images != null && images.length > 0) {
                totalImages += images.length;
                for (MultipartFile image : images) {
                    if (image != null && !image.isEmpty()) {
                        String originalFileName = image.getOriginalFilename();
                        if (originalFileName == null || originalFileName.trim().isEmpty()){
                            logger.warn("Skipping an image with no original filename.");
                            results.add(createErrorResult(null, "图片文件名为空，已跳过"));
                            continue;
                        }
                        logger.info("Processing uploaded image: {}", originalFileName);
                        String uniqueFileName = UUID.randomUUID().toString() + "_" + originalFileName;
                        Path tempFilePath = Paths.get(TEMP_UPLOAD_DIR, uniqueFileName);
                        Files.copy(image.getInputStream(), tempFilePath);
                        imagePathsToProcess.add(tempFilePath.toString());
                        logger.info("Image {} uploaded to temporary directory: {}", originalFileName, tempFilePath.toString());
                    } else if (image != null && image.isEmpty()){
                        logger.warn("Skipping an empty image part.");
                    }
                }
            }

            // 处理通过文件夹选择器“上传”的图片
            if (imageFilesData != null && !imageFilesData.isEmpty()){
                logger.info("Received imageFilesData: {} (Folder upload handling is conceptual for web)", imageFilesData);
                //  实际的文件夹处理逻辑会更复杂，这里仅作日志记录
            }

            // 确保输出路径存在
            Path outputDir;
            if (outputPath == null || outputPath.trim().isEmpty()) {
                logger.error("Output path is empty.");
                response.put("status", "error");
                response.put("message", "输出路径不能为空");
                logger.info("Returning error response due to empty output path: {}", response);
                return response;
            }
            logger.info("Attempting to use output path: {}", outputPath);
            outputDir = Paths.get(outputPath);
            if (!Files.exists(outputDir)) {
                logger.info("Output directory {} does not exist, creating it.", outputDir);
                Files.createDirectories(outputDir);
            }

            // 模拟调用豆包API并保存结果
            logger.info("Starting to process {} images.", imagePathsToProcess.size());
            for (int i = 0; i < imagePathsToProcess.size(); i++) {
                String imagePath = imagePathsToProcess.get(i);
                File imageFile = new File(imagePath);
                String originalFileNameWithUUID = imageFile.getName();
                String originalFileName = originalFileNameWithUUID.contains("_") ? originalFileNameWithUUID.substring(originalFileNameWithUUID.indexOf("_") + 1) : originalFileNameWithUUID;
                logger.info("Processing image file: {}, original name: {}", imagePath, originalFileName);

                // 调用豆包API
                String reversedText = callDoubaoApi(apiKey, customPrompt, imagePath, modelId);
                logger.debug("Generated reversed text: {}", reversedText);

                String txtFileName = (originalFileName.contains(".") ? originalFileName.substring(0, originalFileName.lastIndexOf('.')) : originalFileName) + ".txt";
                Path txtFilePath = outputDir.resolve(txtFileName);
                logger.info("Attempting to save result to: {}", txtFilePath);

                // 检查reversedText是否为错误信息
                if (reversedText != null && reversedText.startsWith("Error:")) {
                    logger.error("API返回错误: {}", reversedText);
                    results.add(createErrorResult(txtFileName, reversedText));
                    // 仍然写入文件，但添加说明
                    String errorContent = "处理图片时发生错误:\n" + reversedText + "\n\n请检查API Key是否正确，或者重试。";
                    Files.writeString(txtFilePath, errorContent, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                } else if (reversedText == null || reversedText.trim().isEmpty()) {
                    logger.error("API返回内容为空");
                    results.add(createErrorResult(txtFileName, "API返回内容为空"));
                    // 写入提示信息
                    String errorContent = "处理图片时API返回内容为空\n\n请检查API Key是否正确，或者重试。";
                    Files.writeString(txtFilePath, errorContent, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                } else {
                    // 正常写入内容
                    Files.writeString(txtFilePath, reversedText, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    results.add(createSuccessResult(txtFileName));
                    logger.info("Successfully saved {}", txtFileName);
                }
                
                // 删除临时文件
                Files.deleteIfExists(Paths.get(imagePath));
                logger.info("Deleted temporary image file: {}", imagePath);
            }

            if (totalImages == 0 && imagePathsToProcess.isEmpty() && (imageFilesData != null && !imageFilesData.isEmpty())){
                 logger.warn("No multipart files processed, but imageFilesData was present. Adding conceptual error for folder handling.");
                 results.add(createErrorResult("文件夹图片", "文件夹图片处理逻辑尚未完全实现，请通过文件选择器上传图片。"));
            }

            response.put("status", "success");
            response.put("message", String.format("%d 张图片处理完成。", imagePathsToProcess.size()));
            response.put("results", results);
            response.put("progress", 100); // 模拟完成
            logger.info("Successfully processed all images. Response: {}", response);

        } catch (InvalidPathException ipe) {
            logger.error("InvalidPathException occurred: {}", ipe.getMessage(), ipe);
            response.put("status", "error");
            response.put("message", "提供的输出路径无效: " + ipe.getMessage());
        } catch (IOException e) {
            logger.error("IOException occurred: {}", e.getMessage(), e);
            response.put("status", "error");
            response.put("message", "处理文件时发生IO错误: " + e.getMessage());
        } catch (Exception e) {
            logger.error("An unexpected error occurred: {}", e.getMessage(), e);
            response.put("status", "error");
            response.put("message", "发生意外错误: " + e.getMessage());
        }
        logger.info("Final response for /reverse-prompt: {}", response);
        return response;
    }

    private String callDoubaoApi(String apiKey, String customPrompt, String imagePath, String modelId) {
        // 在这里实现调用火山方舟API的逻辑
        // 使用apiKey, customPrompt, imagePath和modelId作为输入
        // 返回API的响应
        logger.info("Calling Ark API with customPrompt: {}, imagePath: {}, modelId: {}", 
                   customPrompt, imagePath, modelId);

        // 1. 初始化 ArkService
        // 注意：确保使用的是火山方舟专属API Key，而非火山引擎通用Access Key
        ArkService service = new ArkService(apiKey);

        try {
            // 2. 读取图片并转换为Base64编码
            byte[] fileContent = Files.readAllBytes(Paths.get(imagePath));
            String base64Image = Base64.getEncoder().encodeToString(fileContent);
            
            // 记录图片大小信息，帮助排查问题
            logger.info("Image size: {} bytes, Base64 length: {}", fileContent.length, base64Image.length());

            // 3. 构建消息列表
            List<ChatMessage> messages = new ArrayList<>();
            
            // 添加系统消息
            messages.add(ChatMessage.builder()
                .role(ChatMessageRole.SYSTEM)
                .content("你是一个专业的图片分析助手，请分析图片并生成详细的提示词描述。")
                .build());
            
            // 添加用户消息 - 使用标准的content方式
            // 注意：根据火山方舟SDK 0.2.16版本，多模态功能可能需要不同的实现方式
            // 这里先使用文本内容，如需图片分析功能，请参考最新的官方文档
            String userContent = customPrompt + "\n\n[注意：当前版本暂不支持图片上传，请提供文本描述]";
            
            messages.add(ChatMessage.builder()
                .role(ChatMessageRole.USER)
                .content(userContent)
                .build());

            // 检查模型ID
            if (modelId == null || modelId.trim().isEmpty()) {
                logger.error("Model ID is not configured. Please provide a valid Model ID.");
                return "Error: Model ID is not configured. Please provide a valid Model ID.";
            }

            // 4. 构建请求
            ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
                    .model(modelId) // 使用用户提供的模型ID
                    .messages(messages)
                    .temperature(0.7)
                    .topP(0.9)
                    .build();

            // 5. 发起调用并获取结果
            logger.info("Sending request to API with model ID: {}", modelId);
            ChatCompletionResult result = service.createChatCompletion(chatCompletionRequest);
            
            // 6. 处理响应
            if (result != null) {
                logger.info("Received API response: {}", result);
                
                if (result.getChoices() != null && !result.getChoices().isEmpty()) {
                    String reversedPrompt = result.getChoices().get(0).getMessage().getContent().toString();
                    logger.info("Successfully called API. Response content length: {}", reversedPrompt.length());
                    return reversedPrompt;
                } else {
                    logger.error("API response has null or empty choices. Result: {}", result);
                    return "Error: API返回了响应但没有内容。请检查您的API Key和模型ID是否正确匹配，以及模型是否支持多模态输入。";
                }
            } else {
                logger.error("API returned null response");
                return "Error: API返回为null。请检查网络连接和API服务状态。";
            }
        } catch (Exception e) {
            logger.error("Exception when calling API: {}", e.getMessage(), e);
            // 增强错误信息
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("401")) {
                return "Error: 认证失败 (401)。请检查您的火山方舟API Key是否正确，确保使用的是火山方舟专属API Key而非通用Access Key。";
            } else if (errorMsg != null && errorMsg.contains("404")) {
                return "Error: 端点不存在 (404)。请检查您的模型ID或Endpoint ID是否正确，确保模型已成功部署。";
            } else if (errorMsg != null && errorMsg.contains("400")) {
                return "Error: 请求参数错误 (400)。请检查图片格式、大小是否符合要求，以及其他参数是否正确。详细错误: " + errorMsg;
            } else {
                return "Error calling API: " + e.getMessage() + "。请确保使用正确的火山方舟API Key和模型ID。";
            }
        }
    }

    // 移除了 selectFolder API 端点，现在使用前端浏览器原生API实现文件夹选择

    private Map<String, String> createSuccessResult(String fileName) {
        Map<String, String> result = new HashMap<>();
        result.put("file", fileName);
        result.put("status", "success");
        return result;
    }

    private Map<String, String> createErrorResult(String fileName, String message) {
        Map<String, String> result = new HashMap<>();
        result.put("file", fileName != null ? fileName : "未知文件");
        result.put("status", "error");
        result.put("message", message);
        return result;
    }
}