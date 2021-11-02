package com.yulaw.ccbapi.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.yulaw.ccbapi.exception.CcbException;
import com.yulaw.ccbapi.exception.CcbExceptionEnum;
import com.yulaw.ccbapi.model.dao.*;
import com.yulaw.ccbapi.model.pojo.*;
import com.yulaw.ccbapi.model.vo.*;
import com.yulaw.ccbapi.service.AdvertisementService;
import com.yulaw.ccbapi.service.QuestionService;
import com.yulaw.ccbapi.service.VideoService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class VideoServiceImpl implements VideoService {

    @Autowired
    VideoMapper videoMapper;

    @Autowired
    TeacherMapper teacherMapper;

    @Autowired
    ChannelMapper channelMapper;

    @Autowired
    CategoryMapper categoryMapper;

    @Autowired
    AdvertisementService advertisementService;

    @Autowired
    QuestionService questionService;

    @Autowired
    RedisTemplate redisTemplate;


    /**
     * Video对象转HotVideoVO并填充属性值
     * @param oldList
     * @return
     */
    @Override
    public ArrayList<HotVideoVO> copyToHotVideo(List<Video> oldList){
        ArrayList<HotVideoVO> resultList = new ArrayList<>();
        for (Video video : oldList) {
            HotVideoVO hotVideoVO = new HotVideoVO();
            BeanUtils.copyProperties(video,hotVideoVO);


            //(视频讲师关联查询)
            List<Teacher> teachers = teacherMapper.selectByVideoId(hotVideoVO.getId());
            ArrayList<String> nameList = new ArrayList<>();
            for (Teacher teacher : teachers) {
                nameList.add(teacher.getTeacherName());
            }
            hotVideoVO.setTeacherNameList(nameList);


            //频道图标查询

            Channel channel = channelMapper.selectByPrimaryKey(video.getChannelId());
            if(channel != null){
                hotVideoVO.setChannelIcon(channel.getIcon());
                hotVideoVO.setChannnelName(channel.getChannelName());
            }
            resultList.add(hotVideoVO);
        }
        return resultList;
    }

    @Override
    @Cacheable(value = "getPageList")
    public PageInfo getPageList(Integer pageNum, Integer pageSize, String orderBy,
                                Long channelId, Long categoryId, String keywords){

        ArrayList<HotVideoVO> resultList;
        List<Video> videoList;
        PageHelper.startPage(pageNum,pageSize,orderBy + " desc");
        if(categoryId == null && channelId == null && keywords == null){
            videoList = videoMapper.findAll();
        }else{
            videoList = videoMapper.selectByChannelIdCategoryIdAndName(channelId,categoryId,keywords);
        }
        if(videoList == null){
            throw new CcbException(CcbExceptionEnum.NO_POINT_EXCEPTION);
        }
        resultList = copyToHotVideo(videoList);
        return new PageInfo(resultList);
    }

    @Override
    @Cacheable(value = "getNew")
    public NewVideoVO getNew(){

        Video video = videoMapper.selectNew();
        if(video != null){
            NewVideoVO newVideoVO = new NewVideoVO();
            BeanUtils.copyProperties(video,newVideoVO);
            return newVideoVO;
        }else {
            return null;
        }
    }

    @Override
    @Cacheable(value = "getHotVideoVO")
    public List<HotVideoVO> getHotVideoVO(){
        ArrayList<HotVideoVO> hotVideoVOS;
        List<Video> videos = videoMapper.selectHotByView();
        if(videos == null){
            throw new CcbException(CcbExceptionEnum.NO_POINT_EXCEPTION);
        }
        hotVideoVOS = copyToHotVideo(videos);
        return hotVideoVOS;
    }

    @Override
    @Cacheable(value = "getVideoById")
    public VideoVO getVideoById(Long id) {


        Video video = videoMapper.selectByPrimaryKey(id);
        if(video == null){
            throw new CcbException(CcbExceptionEnum.DATA_NOT_FOUND);
        }

        VideoVO videoVO = new VideoVO();
        BeanUtils.copyProperties(video, videoVO);
        Channel channel = channelMapper.selectByPrimaryKey(videoVO.getChannelId());
        if(channel != null){
            videoVO.setChannelName(channel.getChannelName());
        }

        //添加讲师(关联查询）
        List<Teacher> teachers = teacherMapper.selectByVideoId(videoVO.getId());
        ArrayList<TeacherForVideoVO> teacherList = new ArrayList<>();
        for (Teacher teacher : teachers) {
            TeacherForVideoVO teacherForVideoVO = new TeacherForVideoVO();
            BeanUtils.copyProperties(teacher,teacherForVideoVO);
            teacherList.add(teacherForVideoVO);
        }
        videoVO.setTeacherList(teacherList);

        //添加广告
        Advertisement advertisement = advertisementService.getAdvByChannelId(videoVO.getChannelId());
        if(advertisement != null){
            AdvertisementVO advertisementVO = new AdvertisementVO();
            BeanUtils.copyProperties(advertisement, advertisementVO);
            videoVO.setAdvertisement(advertisementVO);
        }


        //添加问卷
        List<QuestionVO> questionVOS = questionService.getQuestionByChannelId(videoVO.getChannelId());
        if(questionVOS == null){
            throw new CcbException(CcbExceptionEnum.NO_POINT_EXCEPTION);
        }
        videoVO.setQuestionList(questionVOS);



        return videoVO;
    }

    @Override
    public void addStarById(Long id, Integer type){
        Video video = videoMapper.selectByPrimaryKey(id);
        if(video == null){
            throw new CcbException(CcbExceptionEnum.DATA_NOT_FOUND);
        }
        if(type == 1){
            //增加一个点赞量到redis
            BoundHashOperations<String,Long,Integer> hashKey = redisTemplate.boundHashOps("enjoy_count");

            if(hashKey.hasKey(video.getId())){
                Integer value2 = hashKey.get(video.getId());
                value2 = value2 + 1;
                hashKey.put(video.getId(), value2);
            }else {
                hashKey.put(video.getId(), 1);
            }
        }
        if(type == 3){
            //增加一个播放量到redis
            BoundHashOperations<String,Long,Integer> hashKey = redisTemplate.boundHashOps("view_count");

            if(hashKey.hasKey(video.getId())){
                Integer value2 = hashKey.get(video.getId());
                value2 = value2 + 1;
                hashKey.put(video.getId(), value2);
            }else {
                hashKey.put(video.getId(), 1);
            }

            // 将video访问量记录到缓存日志
            BoundHashOperations<String,String,Integer> log = redisTemplate.boundHashOps("video_view");

            if(log.hasKey(video.getVideoTitle())){
                //FIXME : 实现自增 BoundHashOperations.increament 报错
                Integer value2 = log.get(video.getVideoTitle());
                value2 = value2 + 1;
                log.put(video.getVideoTitle(), value2);
            }else {
                log.put(video.getVideoTitle(), 1);
            }
        }
        if (type == 2){
            //增加一个分享量到redis
            BoundHashOperations<String,Long,Integer> shareCount = redisTemplate.boundHashOps("share_count");

            if(shareCount.hasKey(video.getId())){
                Integer value2 = shareCount.get(video.getId());
                value2 = value2 + 1;
                shareCount.put(video.getId(), value2);
            }else {
                shareCount.put(video.getId(), 1);
            }


            // 将video访问量记录到缓存日志
            BoundHashOperations<String,String,Integer> hashKey = redisTemplate.boundHashOps("video_share");

            if(hashKey.hasKey(video.getVideoTitle())){
                //FIXME : 实现自增 BoundHashOperations.increament 报错
                Integer value2 = hashKey.get(video.getVideoTitle());
                value2 = value2 + 1;
                hashKey.put(video.getVideoTitle(), value2);
            }else {
                hashKey.put(video.getVideoTitle(), 1);
            }
        }
    }

    @Override
    @Cacheable(value = "getNextVideoById")
    public VideoVO getNextVideoById(Long id) {
        Video video = videoMapper.selectByPrimaryKey(id);
        if(video == null){
            throw new CcbException(CcbExceptionEnum.DATA_NOT_FOUND);
        }
        List<Video> videos = videoMapper.selectByChannelId(video.getChannelId());
        Long resultId = null;
        int flag = 0;
        VideoVO videoVO;
        for (Video video1 : videos) {
            if(video1.getId().equals(id)){
                flag = 1;
                continue;
            }
            if(flag == 1){
                resultId = video1.getId();
                break;
            }
        }
        if(resultId == null){
            videoVO  = getVideoById(videos.get(0).getId());
        }else {
            videoVO = getVideoById(resultId);
        }
        return videoVO;
    }

    @Override
    @Cacheable(value = "searchVideoById")
    public List<TinyVideoVO> searchVideo(String title){
        List<Video> videos = videoMapper.selectByChannelIdCategoryIdAndName(null, null, title);
        ArrayList<TinyVideoVO> tinyVideoVOS = new ArrayList<>();
        for (Video video : videos) {
            TinyVideoVO tinyVideoVO = new TinyVideoVO();
            BeanUtils.copyProperties(video,tinyVideoVO);
            tinyVideoVOS.add(tinyVideoVO);
        }
        return tinyVideoVOS;
    }
}
