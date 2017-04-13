import java.io.File;
import java.util.Vector;

import org.bytedeco.javacv.*;
import org.bytedeco.javacpp.*;
import org.bytedeco.javacpp.indexer.*;

import org.bytedeco.javacpp.RealSense;
import org.bytedeco.javacpp.RealSense.context;
import org.bytedeco.javacpp.RealSense.device;

import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_imgproc.*;

public class RSPCN implements Runnable{

    // Variables
    Thread thread;

    private static context context = null;
    private static device device = null;

    private static Vector<Passenger> passengers = new Vector<Passenger>(1,1);

    private int imageWidth;
    private int imageHeight;
    private int fps;
    private int cameraType;

    private boolean halt = false;
    private boolean bareMetalMode = false;
    private boolean videoRecordMode = false;

    private static int cnt_out = 0;
    private static int cnt_in = 0;

    private static String devName = null;
    private static float scale;

    private int blurSize = 3;

    private int maxPassengerAge = 2;

    private int max1PassArea = 60000;
    private int max2PassArea = 90000;

    private int thresholdCentimeters = 43; 

    private int xNear = 40;
    private int yNear = 90;

    // Constructor
    RSPCN(int devNumber) {

        context = new context();
        device = context.get_device(devNumber);

        String devName = device.get_name().getString();

        // Camera settings
        if(devName.equals("Intel RealSense R200")) {
            cameraType = 0;

            imageWidth = 320;
            imageHeight = 240;
            fps = 60;
        }
        else if (devName.equals("Intel RealSense SR300")) {
            cameraType = 1;

            imageWidth = 640;
            imageHeight = 480;
            fps = 30;
        }
        device.enable_stream(RealSense.color, imageWidth, imageHeight, RealSense.rgb8, fps);
        device.enable_stream(RealSense.depth, imageWidth, imageHeight, RealSense.z16, fps);

        scale = device.get_depth_scale();
    }

    // Methods
    public void setCameraPresets(int value) {

        if(cameraType == 0) {
            System.out.println( "Presets are not supported for R200 camera");
        }
        else {
            RealSense.apply_ivcam_preset(device, value);
        }

        return;
    }

    public void start(){
        thread = new Thread(this, "Counting thread");
        thread.start();
    }

    public void run() {
        int pid = 0;

        device.start();

        OpenCVFrameConverter.ToIplImage converterToIpl = new OpenCVFrameConverter.ToIplImage();

        IplImage colorImage = null;
        IplImage depthImage = null;
        IplImage frameImage = IplImage.create(imageWidth, imageHeight, IPL_DEPTH_8U, 1);
        IplImage trackImage = IplImage.create(imageWidth, imageHeight, IPL_DEPTH_8U, 1);


        CanvasFrame colorFrame = null;
        // CanvasFrame depthFrame = null;
        CanvasFrame trackFrame = null;

        if(!bareMetalMode) {
            colorFrame = new CanvasFrame("Color Stream",1); 
            // depthFrame = new CanvasFrame("Depth Stream",1); 
            trackFrame = new CanvasFrame("Track Stream",1); 
        }

        FFmpegFrameRecorder recorderColor = null;
        FFmpegFrameRecorder recorderTrack = null;

        try {

            if(videoRecordMode) {
                recorderColor = FFmpegFrameRecorder.createDefault("color.mp4", imageWidth, imageHeight);
                recorderColor.setFrameRate(fps);
                recorderColor.start();
                
                recorderTrack = FFmpegFrameRecorder.createDefault("track.mp4", imageWidth, imageHeight);
                recorderTrack.setFrameRate(fps);
                recorderTrack.start();
            }

            CvMemStorage contours = CvMemStorage.create();

            // Frame capture loop
            while(!halt) {
                device.wait_for_frames();

                // Grab data from RealSense camera
                colorImage = grabColorImage();
                depthImage = grabDepthImage();
                frameImage = grabFrameImage(depthImage);

                // Drawing line
                CvScalar colorred = new CvScalar( 0, 255, 0, 255);
                CvPoint p1 = new CvPoint(0,colorImage.height()/2);
                CvPoint p2 = new CvPoint(colorImage.width(), colorImage.height()/2);
                cvLine( colorImage,
                      p2,       //Starting point of the line
                      p1,       //Ending point of the line
                      colorred, //Color
                      2,        //Thickness
                      8,        //Linetype
                      0);

                // Blurring image
                cvSmooth(frameImage, frameImage, CV_GAUSSIAN, blurSize, blurSize, 0, 0);
                trackImage = frameImage.clone(); 

                // Finding contours
                CvSeq hierarchy = new CvSeq(null); // This is where contours will be accessed
                cvFindContours(frameImage, contours, hierarchy, Loader.sizeof(CvContour.class), CV_RETR_EXTERNAL, CV_CHAIN_APPROX_NONE);

                while (hierarchy != null && !hierarchy.isNull()) {

                    if(hierarchy.elem_size() > 0) {

                        // Find polygon that approximate detected contours
                        CvSeq points = cvApproxPoly(hierarchy, Loader.sizeof(CvContour.class), contours, CV_POLY_APPROX_DP, cvContourPerimeter(hierarchy)*0.02, 0);

                        double areaCurrentObject = Math.abs(cvContourArea(points, CV_WHOLE_SEQ, 0)) ;

                        if(areaCurrentObject > 20000) {

                            // Find bounding rectangle of detected shape
                            CvRect br = cvBoundingRect(hierarchy);

                            // Find center of bounding rectangle of detected shape
                            CvPoint rectCenter = new CvPoint( (int)(br.x() + br.width()/2), (int)(br.y() + br.height()/2));

                            // Drawing rectangle
                            int x = br.x(), y = br.y(), w = br.width(), h = br.height();
                            // cvRectangle(trackImage, cvPoint(x, y), cvPoint(x+w, y+h), CvScalar.WHITE, 1, CV_AA, 0);
                            cvRectangle(colorImage, cvPoint(x, y), cvPoint(x+w, y+h), CvScalar.GREEN, 1, CV_AA, 0);
                            // Drawing rectangle center
                            // cvCircle(trackImage, rectCenter, 5, CvScalar.WHITE, 2, CV_AA, 0);
                            cvCircle(colorImage, rectCenter, 5, CvScalar.RED, 2, CV_AA, 0);

                            boolean newPassenger = true;
                            for(int i = 0; i < passengers.size(); i++) {
                                //If passenger is near a known passenger assume they are the same one
                                if( abs(rectCenter.x() - passengers.elementAt(i).getX()) <= xNear  && 
                                    abs(rectCenter.y() - passengers.elementAt(i).getY()) <= yNear  ) {

                                    newPassenger = false;
                                    passengers.elementAt(i).updateCoords(rectCenter);

                                    // -- COUNT
                                    if(passengers.elementAt(i).getTracks().size() > 1)
                                    {
                                       // Up to down 
                                       if( passengers.elementAt(i).getLastPoint().y() < frameImage.height()/2 &&  passengers.elementAt(i).getCurrentPoint().y() >= frameImage.height()/2  ||
                                           passengers.elementAt(i).getLastPoint().y() <= frameImage.height()/2 &&  passengers.elementAt(i).getCurrentPoint().y() > frameImage.height()/2 ) {

                                            // Counting multiple passenger depending on area size
                                            if (areaCurrentObject > max1PassArea && areaCurrentObject < max2PassArea)
                                                cnt_out += 2;
                                            else if (areaCurrentObject > max2PassArea)
                                                cnt_out += 3;
                                            else
                                                cnt_out++;

                                       }

                                       // Down to up
                                       if( passengers.elementAt(i).getLastPoint().y() > frameImage.height()/2 &&  passengers.elementAt(i).getCurrentPoint().y() <= frameImage.height()/2  ||
                                           passengers.elementAt(i).getLastPoint().y() >= frameImage.height()/2 &&  passengers.elementAt(i).getCurrentPoint().y() < frameImage.height()/2 ) {

                                            // Counting multiple passenger depending on area size
                                            if (areaCurrentObject > max1PassArea && areaCurrentObject < max2PassArea)
                                                cnt_in += 2;
                                            else if (areaCurrentObject > max2PassArea)
                                                cnt_in += 3;
                                            else
                                                cnt_in++;

                                       }


                                    }

                                    break;
                                }

                            }

                            if(newPassenger) {

                                Passenger pass = new Passenger(pid, rectCenter, 0);
                                passengers.add(pass);
                                pid++;

                            }
                        }
                    }

                    hierarchy = hierarchy.h_next();

                }

                // Draw trajectories and update age
                for(int i = 0; i < passengers.size(); i++) {

                    if(passengers.elementAt(i).getTracks().size() > 1) {

                        for(int j = 0; j < passengers.elementAt(i).getTracks().size() - 1 ; j++) {
                            cvLine(colorImage,
                                   passengers.elementAt(i).getTracks().elementAt(j),
                                   passengers.elementAt(i).getTracks().elementAt(j + 1),
                                   passengers.elementAt(i).getTrackColor(),
                                   2,
                                   8,
                                   0);
                        }
                    }

                    passengers.elementAt(i).updateAge();

                    if(passengers.elementAt(i).getAge() > (maxPassengerAge * fps)) {
                        passengers.remove(i);
                    }
                }

                // Write current count
                CvPoint cntInLoc = new CvPoint(0, colorImage.height() - 30);
                CvPoint cntOutLoc = new CvPoint(0, colorImage.height() - 10);
                CvFont font = cvFont(1,1);

                cvPutText(colorImage, "Count IN:  " + cnt_in , cntInLoc , font, CvScalar.WHITE);
                cvPutText(colorImage, "Count OUT: " + cnt_out, cntOutLoc, font, CvScalar.WHITE);

                // Display streams using Java frame 
                if(!bareMetalMode) {
                    colorFrame.showImage(converterToIpl.convert(colorImage));
                    // depthFrame.showImage(converterToIpl.convert(depthImage));
                    trackFrame.showImage(converterToIpl.convert(trackImage));
                }

                if(videoRecordMode) {
                    recorderColor.record(converterToIpl.convert(colorImage));
                    recorderTrack.record(converterToIpl.convert(trackImage));
                }

                // cvSaveImage("color.jpg", colorImage);
                // cvSaveImage("depth.jpg", depthImage);
                // cvSaveImage("frame.jpg", frameImage);
            }

            if(videoRecordMode) {
                recorderColor.stop();
                recorderTrack.stop();
            }

            colorImage.release();
            depthImage.release();
            frameImage.release();
            trackImage.release();

            if(!bareMetalMode) {
                colorFrame.dispose();
                // depthFrame.dispose();
                trackFrame.dispose();
            }

            device.stop();
                
            device.disable_stream(RealSense.color);
            device.disable_stream(RealSense.depth);

        } catch (Exception e) {
            System.out.println( e );
        }

        return;

    }

    public IplImage grabColorImage() {

        Pointer rawVideoImageData = new Pointer((Pointer) null);
        IplImage rawVideoImage = null;

        rawVideoImageData = device.get_frame_data(RealSense.color);

        int iplDepth = IPL_DEPTH_8U, channels = 3;
        int deviceWidth = device.get_stream_width(RealSense.color);
        int deviceHeight = device.get_stream_height(RealSense.color);

        rawVideoImage = IplImage.createHeader(deviceWidth, deviceHeight, iplDepth, channels);

        cvSetData(rawVideoImage, rawVideoImageData, deviceWidth * channels * iplDepth / 8);

        if (channels == 3) {
            cvCvtColor(rawVideoImage, rawVideoImage, CV_BGR2RGB);
        }

        return rawVideoImage;
    }

    public IplImage grabDepthImage() {

        Pointer rawDepthImageData = new Pointer((Pointer) null);
        IplImage rawDepthImage = null;

        rawDepthImageData = device.get_frame_data(RealSense.depth);

        int iplDepth = IPL_DEPTH_16U, channels = 1;
        int deviceWidth = device.get_stream_width(RealSense.depth);
        int deviceHeight = device.get_stream_height(RealSense.depth);

        rawDepthImage = IplImage.createHeader(deviceWidth, deviceHeight, iplDepth, channels);

        cvSetData(rawDepthImage, rawDepthImageData, deviceWidth * channels * iplDepth / 8);

        return rawDepthImage;
    }

    public IplImage grabFrameImage(IplImage src) {

        IplImage dst = IplImage.create(imageWidth, imageHeight, IPL_DEPTH_8U, 1);

        UShortRawIndexer srcIdx = src.createIndexer();
        UByteRawIndexer dstIdx = dst.createIndexer();

        final int rows = src.height();
        final int cols = src.width();

        // Intelligent threshold
        final int thresPixel = (int)(thresholdCentimeters / (100 * scale));

        // Parallel computation: we need speed
        Parallel.loop(0, rows, new Parallel.Looper() { 
        public void loop(int from, int to, int looperID) {

        for(int i = 0; i < rows; i++) {

            for(int j = 0; j < cols; j++) {

                double p = srcIdx.get(i, j, 0);

                // Threshold
                // NODATA: 0 => To farthest value(65535)
                // THRESH: X => To farthest value(65535)
                if(p > thresPixel || p == 0)
                    p = 65535;

                // Conversion to 8bit values and mapping
                // 0   => Farthest point
                // 255 => Nearest point
                dstIdx.put(i, j, 0, 255 - (int)(p * 255.0 / 65535));

            }
        }}});

        srcIdx.release();
        dstIdx.release();

        return dst;
    }

    public void stop()
    {
        this.halt = true;
    }

    public void resetCounters() {
        cnt_out = 0;
        cnt_in  = 0;
    }

    // Getters and Setters
    public static String getDeviceName() {
        return device.get_name().getString();
    }

    public static String getDeviceSerial() {
        return device.get_serial().getString();
    }

    public static String getDeviceFirmware() {
        return device.get_firmware_version().getString();
    }

    public static int getCnt_out()
    {
        return cnt_out;
    }

    public static int getCnt_in()
    {
        return cnt_in;
    }

    public int getMaxPassengerAge()
    {
        return maxPassengerAge;
    }

    public void setMaxPassengerAge(int maxPassengerAge)
    {
        this.maxPassengerAge = maxPassengerAge;
    }

    public int getMax1PassArea()
    {
        return max1PassArea;
    }

    public void setMax1PassArea(int max1PassArea)
    {
        this.max1PassArea = max1PassArea;
    }

    public int getMax2PassArea()
    {
        return max2PassArea;
    }

    public void setMax2PassArea(int max2PassArea)
    {
        this.max2PassArea = max2PassArea;
    }

    public int getThresholdCentimeters()
    {
        return thresholdCentimeters;
    }

    public void setThresholdCentimeters(int thresholdCentimeters)
    {
        this.thresholdCentimeters = thresholdCentimeters;
    }

    public int getXNear()
    {
        return xNear;
    }

    public void setXNear(int xNear)
    {
        this.xNear = xNear;
    }

    public int getYNear()
    {
        return yNear;
    }

    public void setYNear(int yNear)
    {
        this.yNear = yNear;
    }

    public int getBlurSize()
    {
        return blurSize;
    }

    public void setBlurSize(int blurSize)
    {
        if(blurSize <= 0 || blurSize % 2 != 1)
            System.out.println( "Error: assertion  blurSize > 0 && blurSize % 2 == 1 failed.");
        else
            this.blurSize = blurSize;
    }
    
    public void setBareMetalMode(boolean bareMetalMode)
    {
        this.bareMetalMode = bareMetalMode;
    }
    
    public void setVideoRecordMode(boolean videoRecordMode)
    {
        this.videoRecordMode = videoRecordMode;
    }
}