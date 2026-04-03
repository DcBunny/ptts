import 'package:go_router/go_router.dart';
import 'package:tiaosheng/features/jump_session/view/jump_session_page.dart';
import 'package:tiaosheng/features/parent_camera/view/parent_camera_page.dart';

final GoRouter appRouter = GoRouter(
  initialLocation: '/session',
  routes: [
    GoRoute(
      path: '/session',
      builder: (context, state) => const JumpSessionPage(),
    ),
    GoRoute(
      path: '/parent-camera',
      builder: (context, state) => const ParentCameraPage(),
    ),
  ],
);
