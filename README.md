# FPAutoMine — addon đào tự động cho FakePlayerPlus

Addon thêm action `automine` cho [FakePlayerPlus](https://github.com/xiplugin/FakePlayerPlus) (Paper 1.21.x). Bot tự đi tìm block mục tiêu và đào, có thể cất đồ vào rương gần đó.

## Cú pháp

```
/fp action start mine <tên block> [số lượng] [tên bot]
```

- **tên block**: tên Material Paper, ví dụ `STONE`, `DIAMOND_ORE`, `OAK_LOG`.
- **số lượng** (tùy chọn): số block cần đào. Bỏ trống, `-` hoặc `*` nghĩa là không giới hạn (đào đến khi túi đầy).
- **tên bot** (tùy chọn): bắt buộc nếu bạn sở hữu nhiều hơn một bot. Nếu chỉ có một bot thì addon tự chọn bot đó.

Ví dụ:

```
/fp action start mine DIAMOND_ORE 64 Bot1      # đào 64 kim cương quặng
/fp action start mine STONE Bot1               # đào đến khi túi đầy
/fp action start mine OAK_LOG 128              # đào 128 gỗ, bot tự chọn
```

Dừng: `/fp action stop mine [tên bot]`.

Quyền cần có: `fakeplayer.action` và `fakeplayer.action.mine`. Người điều khiển phải là chủ bot hoặc có `fakeplayer.admin`.

## Hành vi

1. Bot tìm block cùng loại gần nhất mà nó có thể đi tới và đào. Nếu không thấy, bot đi khám phá ngẫu nhiên 16–32 block.
2. Đào xong, bot nhặt đồ rơi trong bán kính 1.5 block và cập nhật số đã đào.
3. Khi đạt chỉ tiêu, hoặc túi đầy (với chế độ không giới hạn):
   - **Có rương trong 10 block**: bot đi tới rương, cất đồ, rồi:
     - đạt chỉ tiêu → dừng và đứng im;
     - túi đầy và không giới hạn → tiếp tục đào.
   - **Không có rương**: bot giữ đồ trong túi và đứng im.
4. Các món sau không bị cất vào rương: thức ăn, công cụ (`_PICKAXE`, `_AXE`, `_SHOVEL`, `_HOE`, `_SWORD`), kéo, khiên, cung, nỏ, elytra.

Lệnh `/fp action start mine` không kèm tham số vẫn mở GUI gốc của FakePlayerPlus.

## Cài đặt

Addon cần FakePlayerPlus đã cài trên server.

### Build

1. Cài API của FakePlayerPlus vào Maven local (trong thư mục repo FakePlayerPlus):

   ```
   ./gradlew :api:publishToMavenLocal
   ```

   Lệnh này tạo artifact `com.coderxi.plugin.fakeplayer:api:dev`, mà `build.gradle.kts` của addon đang dùng.

2. Build addon (cần JDK 21, dùng Gradle đã cài sẵn hoặc `gradle` thay cho `./gradlew`):

   ```
   ./gradlew build
   ```

3. Lấy file `build/libs/FPAutoMine-1.0.0.jar`, đặt vào thư mục `plugins/` của server và khởi động lại.

## Cấu trúc mã nguồn

| File | Vai trò |
|---|---|
| `AutoMineAction` | Định nghĩa action `automine` |
| `AutoMineHandler` | Nhận tick từ FakePlayerPlus, giữ phiên của từng bot theo UUID |
| `AutoMineSession` | Máy trạng thái: lập kế hoạch → đi → đào → xác nhận → cất đồ |
| `Pathfinder` | Tìm đường A* trên lưới block, có chế độ khám phá |
| `CommandListener` | Chặn lệnh `/fp action start mine ...` và khởi chạy action |
| `FPAutoMinePlugin` | Điểm vào của plugin, đăng ký action và listener |

## Hạn chế và trạng thái kiểm thử

- **Chưa chạy trên server thật.** Mã nguồn đã được biên dịch từng file với source Paper API và stub của API FakePlayerPlus, không có lỗi trong addon. Hành vi khi chạy (di chuyển, đào, cất đồ) chưa được kiểm chứng.
- Không hỗ trợ Folia. Addon tự tắt nếu phát hiện Folia.
- Trong GUI, nhãn action hiển thị là `fakeplayer.action.automine` vì chưa có bản dịch.
- `/fp action execute automine` không kiểm tra quyền `fakeplayer.action.mine` vì type này không khai báo quyền.
- Bot chỉ nhặt đồ rơi trong bán kính 1.5 block.
- Nếu bot bị kẹt khi đi, addon dịch chuyển bot tới điểm tiếp theo trên đường đi.
