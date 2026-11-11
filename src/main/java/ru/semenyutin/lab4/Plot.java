package ru.semenyutin.lab4;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

final class Plot {
    private static final int W=920,H=310,L=75,R=865,Y=132;
    private static final String INK="#26354a", BLUE="#245c89", FILL="#cfe8f8", RED="#bb374d";
    private final BufferedImage image=new BufferedImage(W*2,H*2,BufferedImage.TYPE_INT_RGB);
    private final StringBuilder svg=new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 920 310\" width=\"920\" height=\"310\">");
    private Graphics2D g;
    static Plot render(Analysis.Result result,boolean violin){return new Plot(result,violin);}
    private Plot(Analysis.Result r,boolean violin){
        g=image.createGraphics();g.scale(2,2);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        try {
            rect(0,0,W,H,"#ffffff",null);
            String title=r.column().title();
            if(title.length()>80)title=title.substring(0,77)+"…";
            label(L,40,title,22);
            var s=r.stats();double range=s.max()-s.min();
            if(!Double.isFinite(range))throw new IllegalArgumentException("Диапазон слишком велик для графика.");
            java.util.function.DoubleUnaryOperator x=v->range==0?(L+R)/2.0:L+(v-s.min())/range*(R-L);
            if(violin)violin(r,s,x,range);
            else{
                line(x.applyAsDouble(s.lowWhisker()),Y,x.applyAsDouble(s.highWhisker()),Y,BLUE);
                for(double v:new double[]{s.lowWhisker(),s.highWhisker()})line(x.applyAsDouble(v),Y-14,x.applyAsDouble(v),Y+14,BLUE);
                double left=x.applyAsDouble(s.q1()),right=x.applyAsDouble(s.q3());
                rect(left,Y-29,Math.max(1,right-left),58,FILL,BLUE);
            }
            line(x.applyAsDouble(s.median()),Y-34,x.applyAsDouble(s.median()),Y+34,BLUE);
            for(double v:s.iqrOutliers())circle(x.applyAsDouble(v),Y,4,RED);
            line(L,232,R,232,INK);
            for(int i=0;i<=5;i++){
                if(range==0&&i>0)break;
                double value=range==0?s.min():s.min()+range*(i/5.0),xx=x.applyAsDouble(value);
                line(xx,232,xx,239,INK);label(xx-24,259,format(value),13);
            }
            label(L,290,(violin?"Скрипичный график":"Ящик с усами")+"  ·  медиана "+format(s.median())+"  ·  красным — выбросы IQR",14);
        } finally {g.dispose();g=null;}
    }
    private void violin(Analysis.Result result,Analysis.Stats s,java.util.function.DoubleUnaryOperator x,double range){
        if(range==0){circle(x.applyAsDouble(s.min()),Y,15,FILL);return;}
        int n=96;
        double bandwidth=Math.max(range/80,Math.min(range/3,1.06*s.deviation()*Math.pow(s.n(),-.2)));
        double[] density=new double[n+1];
        double peak=0;
        int stride=Math.max(1,result.values().size()/3000);
        for(int i=0;i<=n;i++){
            double v=s.min()+range*i/n, sum=0;
            for(int j=0;j<result.values().size();j+=stride){double z=(v-result.values().get(j))/bandwidth;sum+=Math.exp(-.5*z*z);}
            density[i]=sum;peak=Math.max(peak,sum);
        }
        if(peak==0)return;
        Path2D.Double path=new Path2D.Double();StringBuilder d=new StringBuilder();
        for(int j=0;j<2;j++)for(int k=0;k<=n;k++){
            int i=j==0?k:n-k;double xx=L+(R-L)*i/(double)n, yy=Y+(j==0?-1:1)*58*density[i]/peak;
            if(j==0&&k==0){path.moveTo(xx,yy);d.append("M");}else{path.lineTo(xx,yy);d.append(" L");}d.append(xx).append(' ').append(yy);
        }
        path.closePath();paint(path,FILL,BLUE);svg.append("<path d=\"").append(d).append(" Z\" fill=\"").append(FILL).append("\" stroke=\"").append(BLUE).append("\"/>");
    }
    private static String format(double x){return String.format(Locale.ROOT,"%.5g",x);}
    private void paint(Shape shape,String fill,String stroke){if(fill!=null){g.setColor(Color.decode(fill));g.fill(shape);}if(stroke!=null){g.setColor(Color.decode(stroke));g.draw(shape);}}
    private void rect(double x,double y,double w,double h,String fill,String stroke){
        paint(new java.awt.geom.Rectangle2D.Double(x,y,w,h),fill,stroke);
        svg.append("<rect x=\""+x+"\" y=\""+y+"\" width=\""+w+"\" height=\""+h+"\" fill=\""+fill+"\" stroke=\""+(stroke==null?"none":stroke)+"\"/>");
    }
    private void line(double x,double y,double x2,double y2,String stroke){
        paint(new java.awt.geom.Line2D.Double(x,y,x2,y2),null,stroke);
        svg.append("<line x1=\""+x+"\" y1=\""+y+"\" x2=\""+x2+"\" y2=\""+y2+"\" stroke=\""+stroke+"\"/>");
    }
    private void circle(double x,double y,int r,String fill){
        paint(new java.awt.geom.Ellipse2D.Double(x-r,y-r,2*r,2*r),fill,null);
        svg.append("<circle cx=\""+x+"\" cy=\""+y+"\" r=\""+r+"\" fill=\""+fill+"\"/>");
    }
    private void label(double x,double y,String s,int size){
        g.setColor(Color.decode(INK));g.setFont(new Font("SansSerif",Font.PLAIN,size));g.drawString(s,(float)x,(float)y);
        svg.append("<text x=\""+x+"\" y=\""+y+"\" font-family=\"sans-serif\" font-size=\""+size+"\" fill=\""+INK+"\">"+escape(s)+"</text>");
    }
    static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    String svg(){return svg+"</svg>";}
    BufferedImage image(){return image;}
    byte[] png()throws IOException{var buffer=new ByteArrayOutputStream();ImageIO.write(image,"png",buffer);return buffer.toByteArray();}
}
