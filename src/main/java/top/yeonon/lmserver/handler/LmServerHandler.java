package top.yeonon.lmserver.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.yeonon.lmserver.controller.LmHttpHandler;
import top.yeonon.lmserver.core.ioc.DefaultBeanProcessor;
import top.yeonon.lmserver.filter.LmFilter;
import top.yeonon.lmserver.http.LmRequest;
import top.yeonon.lmserver.http.LmResponse;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.ListIterator;

/**
 * 属于Netty框架下的的handler，处于进站方向的最后一个
 * @Author yeonon
 * @date 2018/5/23 0023 19:14
 **/
public class LmServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(LmServerHandler.class);

    private static final String STATIC_PATH = "static";

    //将对象转换成JSON格式的字符串需要用的
    private ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest fullHttpRequest) throws Exception {
        //构建LmRequest和LmResponse
        LmRequest request = LmRequest.build(ctx, fullHttpRequest);
        LmResponse response = LmResponse.build(ctx, request);

        //获取请求路径
        String path = request.getPath();

        List<LmFilter> filters = DefaultBeanProcessor.getFilter(path);

        //执行前置filter
        doBeforeFilter(filters, request);

        if (StringUtils.isNotBlank(path) && path.endsWith(".html")) {
            sendHtml(request, response, path);
        } else {
            sendNormalContent(request, response, path);
        }

        //执行后置filter
        doAfterFilter(filters, response);

    }

    /**
     * 发送HTML静态内容
     * @param request 请求
     * @param response 响应
     * @param path HTML所在路径
     */
    private void sendHtml(LmRequest request, LmResponse response, String path) {
        String fileName = LmServerHandler.class.getResource("/").getPath() + STATIC_PATH + path;
        File file = new File(fileName);
        if (file.exists())
            response.setContent(file).setContentType(LmResponse.ContentTypeValue.HTML_CONTENT).send();
        else {
            response.sendError("File not found!", HttpResponseStatus.NOT_FOUND);
        }
    }


    /**
     * 发送普通文本
     * @param request 请求
     * @param response 响应
     * @param path 路径
     * @throws JsonProcessingException
     * @throws InvocationTargetException
     * @throws IllegalAccessException
     */
    private void sendNormalContent(LmRequest request, LmResponse response, String path) throws JsonProcessingException, InvocationTargetException, IllegalAccessException {
        LmHttpHandler handler = DefaultBeanProcessor.getHandler(path.trim());
        //handler不为null的话，就正常执行url对应的处理方法，并将返回值写回客户端
        if (handler != null) {
            Object message = handler.execute(request);
            if (message == null) {
                //如果消息为null，也许是参数错误，或者服务端出现异常，例如读写数据库异常等
                response.sendError("服务器异常或者参数错误", HttpResponseStatus.valueOf(500));
            } else {
                response.setContent(objectMapper.writeValueAsString(message))
                        .setContentType(LmResponse.ContentTypeValue.JSON_CONTENT)
                        .send();
            }
        } else {
            //handler为null，即没有url映射，这个时候应该返回404错误
            response.sendError("404 Not Found", HttpResponseStatus.NOT_FOUND);
        }

    }

    /**
     * 在执行业务逻辑之前执行
     * @param filters 过滤器
     * @param lmRequest 请求
     */
    private void doBeforeFilter(List<LmFilter> filters, LmRequest lmRequest) {
        if (filters == null) return;
        for (LmFilter filter : filters) {
            filter.before(lmRequest);
        }
    }

    /**
     * 在执行业务逻辑之后
     * @param filters 过滤器
     * @param lmResponse 响应
     */
    private void doAfterFilter(List<LmFilter> filters, LmResponse lmResponse) {
        if (filters == null) return;
        //这里要方向遍历

        //只能使用迭代器多遍历一次来实现，因为不能更改list的顺序，否则会在其他地方增加开销
        ListIterator<LmFilter> li = filters.listIterator();
        while (li.hasNext()) {
            li.next();
        }
        while (li.hasPrevious()) {
            li.previous().after(lmResponse);
        }

    }

    /**
     * 异常捕获，在Netty中，这应该是Pipeline中最后的Handler实现的方法
     * @param ctx ChannelHandlerContext
     * @param cause Throwable
     * @throws Exception
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}
