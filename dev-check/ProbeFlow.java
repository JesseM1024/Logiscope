import com.jessetech.logiscope.analysis.ChartData;
import com.jessetech.logiscope.analysis.CurveMath;
import com.jessetech.logiscope.analysis.Series;
import com.jessetech.logiscope.analysis.SeriesPoint;
import com.jessetech.logiscope.store.Sample;

import java.util.ArrayList;
import java.util.List;

/** 一次性探针：连续 3 格存量 10 → 14 → 12，看 IO 统计到底报了什么（不入库） */
public class ProbeFlow {
    public static void main(String[] args) {
        List<Sample> samples = new ArrayList<>();
        samples.add(new Sample(100, "iron", 10));
        samples.add(new Sample(101, "iron", 14));
        samples.add(new Sample(102, "iron", 12));
        ChartData data = ChartData.build(1, samples, 16);
        for (Series s : data.series()) {
            System.out.println("line = " + s.itemId() + "  breaks=" + s.breaks());
            System.out.print("  stock points:");
            for (SeriesPoint p : s.points()) {
                System.out.print(" (t=" + p.tick() + " v=" + p.count() + " brk=" + p.breakBefore() + ")");
            }
            System.out.println();
            CurveMath.TotalChange stock = CurveMath.totalChange(s.points());
            System.out.println("  stock 统计: 上升=" + stock.rise() + " 下降=" + stock.fall() + " 断档=" + stock.gaps());
            List<SeriesPoint> flow = s.flow();
            System.out.print("  flow  points:");
            for (SeriesPoint p : flow) {
                System.out.print(" (t=" + p.tick() + " v=" + p.count() + " brk=" + p.breakBefore() + ")");
            }
            System.out.println();
            CurveMath.TotalChange ft = CurveMath.flowTotal(flow);
            CurveMath.Events fe = CurveMath.flowEvents(flow);
            System.out.println("  flow  统计: 上升=" + ft.rise() + " 下降=" + ft.fall() + " 断档=" + ft.gaps()
                    + " | 事件=" + fe.count());
        }
        System.out.println();
        System.out.println("真相：10→14（+4）与 14→12（-2）两格都观测到了 ⇒ 期望 上升=4 下降=-2 断档=0 事件=2");
    }
}
