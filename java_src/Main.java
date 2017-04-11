import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import org.bytedeco.javacv.*;
import org.bytedeco.javacpp.*;
import org.bytedeco.javacpp.indexer.*;

import org.bytedeco.javacpp.RealSense;
import org.bytedeco.javacpp.RealSense.context;
import org.bytedeco.javacpp.RealSense.device;

import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_imgproc.*;
import static org.bytedeco.javacpp.opencv_imgcodecs.*;
import static org.bytedeco.javacpp.opencv_highgui.*;

public class Main {

    private static context context = null;
    private static device device = null;

    public static void main(String[] args) {

        context = new context();
        System.out.println("Devices found: " + context.get_device_count());

        device = context.get_device(0);
        System.out.println("Using device 0, an " + device.get_name().getString());
        System.out.println("Using firmware version: " +  device.get_firmware_version().getString()); 
        System.out.println("Usb port id: " + device.get_usb_port_id().getString());


        device.enable_stream(RealSense.color, 640, 480, RealSense.rgb8, 30);
        device.enable_stream(RealSense.depth, 640, 480, RealSense.z16, 30);

        device.start();

        OpenCVFrameConverter.ToIplImage converterToIpl = new OpenCVFrameConverter.ToIplImage();

        IplImage colorImage = null;
        IplImage depthImage = null;
        IplImage frameImage = IplImage.create(640, 480, IPL_DEPTH_8U, 1);

        CanvasFrame colorFrame = new CanvasFrame("Color Stream",1); 
        CanvasFrame depthFrame = new CanvasFrame("Depth Stream",1); 
        CanvasFrame frameFrame = new CanvasFrame("Frame Stream",1); 

        CvMemStorage contours = CvMemStorage.create();

        // Frame capture loop
        while(true) {
            device.wait_for_frames();

            // Grab data from RealSense camera
            colorImage = grabColorImage();
            depthImage = grabDepthImage();

            // Convert and threshold depth image
            frameImage = grabFrameImage(depthImage);

            // TODO: rest of the algorithm
            // ...

            // Conversion needed 
            Mat colorMat = new Mat(colorImage);
            // Mat frameMat = new Mat(frameImage);

            // Drawing line
            Scalar colorred = new Scalar( 0, 255, 0, 255);
            Point p1 = new Point(0,colorMat.rows()/2);
            Point p2 = new Point(colorMat.cols(), colorMat.rows()/2);
            line( colorMat,
                  p2,       //Starting point of the line
                  p1,       //Ending point of the line
                  colorred, //Color
                  2,        //Thickness
                  8,        //Linetype
                  0);       

            // Blurring image
            // Size blur_k_size = new Size(3, 3);
            // blur(frameMat, frameMat, blur_k_size);

            // Finding contours
            CvSeq hierarchy = new CvSeq(null);
            int ldrSz = Loader.sizeof(CvContour.class);
            cvFindContours(frameImage, contours, hierarchy, ldrSz, CV_RETR_EXTERNAL, CV_CHAIN_APPROX_NONE);

            while (hierarchy != null && !hierarchy.isNull()) {

                if(hierarchy.elem_size() > 0) {
                    // How can I access contours here?
                    // double areaCurrentObject = contourArea(contours[idx]);

                    CvSeq points = cvApproxPoly(hierarchy, Loader.sizeof(CvContour.class), contours, CV_POLY_APPROX_DP, cvContourPerimeter(hierarchy)*0.02, 0);

                    if(Math.abs(cvContourArea(points, CV_WHOLE_SEQ, 0)) > 10000) {

                        // cvDrawContours(colorImage, points, CvScalar.GREEN, CvScalar.GREEN, -1, 1, CV_AA);
                        // Moments M = moments(hierarchy);
                        // Point2f mc = Point2f( M.m10/M.m00 , M.m01/M.m00 );

                        CvRect br = cvBoundingRect(hierarchy);
                        CvPoint rectCenter = new CvPoint( (int)(br.x() + br.width()/2), (int)(br.y() + br.height()/2));

                        // Drawing rectangle
                        int x = br.x(), y = br.y(), w = br.width(), h = br.height();
                        cvRectangle(colorImage, cvPoint(x, y), cvPoint(x+w, y+h), CvScalar.GREEN, 1, CV_AA, 0);

                        // Drawing rectangle center
                        cvCircle(colorImage, rectCenter, 5, CvScalar.RED, 2, CV_AA, 0);

                    }
                }

                hierarchy = hierarchy.h_next();
                
            }

            // Display streams using Java frame 
            colorFrame.showImage(converterToIpl.convert(colorImage));
            depthFrame.showImage(converterToIpl.convert(depthImage));
            frameFrame.showImage(converterToIpl.convert(frameImage));
            
            // cvSaveImage("color.jpg", colorImage);
            // cvSaveImage("depth.jpg", depthImage);
            // cvSaveImage("frame.jpg", frameImage);
        }

    }
    
    public static IplImage grabColorImage() {

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

    public static IplImage grabDepthImage() {

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

    public static IplImage grabFrameImage(IplImage src) {

        IplImage dst = IplImage.create(640, 480, IPL_DEPTH_8U, 1);

        UShortRawIndexer srcIdx = src.createIndexer();
        UByteRawIndexer dstIdx = dst.createIndexer();

        final int rows = src.height();
        final int cols = src.width();

        // Parallel computation: we need speed
        Parallel.loop(0, rows, new Parallel.Looper() { 
        public void loop(int from, int to, int looperID) {

        for(int i = 0; i < rows; i++) {

            for(int j = 0; j < cols; j++) {

                double p = srcIdx.get(i, j, 0);
                

                if(p == 0)
                     p = 65535;

                // TODO: Implement intellingent threshold with distance conversion
                // Threshold
                if(p > 8000)
                    p = 65535;

                dstIdx.put(i, j, 0, 255 - (int)(p * 255.0 / 65535));

            }
        }}});

        srcIdx.release();
        dstIdx.release();

        return dst;
    }

}


