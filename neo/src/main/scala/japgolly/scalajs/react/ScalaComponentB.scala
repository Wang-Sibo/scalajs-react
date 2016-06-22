package japgolly.scalajs.react

import scala.{Either => Or}
import scalajs.js
import japgolly.scalajs.react.internal._
import ScalaComponent._

object ScalaComponentB {

  type InitStateFnU[P, S]    = Component.Unmounted[P, Any]
  type InitStateArg[P, S]    = (InitStateFnU[P, S] => S) Or js.Function0[Box[S]]
  type NewBackendFn[P, S, B] = BackendScope[P, S] => B
  type RenderFn    [P, S, B] = MountedCB[P, S, B] => raw.ReactElement
  // Technically ↗ MountedCB ↗ should be Unmounted but because it can used to create event callbacks
  // (eg. onclick --> $.setState), it needs access to all Mounted methods.

  private val InitStateUnit : Nothing Or js.Function0[Box[Unit]] =
    Right(() => Box.Unit)

  // ===================================================================================================================

  final class Step1[P](name: String) {
    type Next[S] = Step2[P, S]

    // getInitialState is how it's named in React
    def getInitialState  [S](f: InitStateFnU[P, S] => S)            : Next[S] = new Step2(name, Left(f))
    def getInitialStateCB[S](f: InitStateFnU[P, S] => CallbackTo[S]): Next[S] = getInitialState(f.andThen(_.runNow()))

    // More convenient methods that don't need the full CompScope
    def initialState    [S](s: => S              ): Next[S] = new Step2(name, Right(() => Box(s)))
    def initialStateCB  [S](s: CallbackTo[S]     ): Next[S] = initialState(s.runNow())
    def initialState_P  [S](f: P => S            ): Next[S] = getInitialState[S]($ => f($.props))
    def initialStateCB_P[S](f: P => CallbackTo[S]): Next[S] = getInitialState[S]($ => f($.props).runNow())

    def stateless: Next[Unit] =
      new Step2(name, InitStateUnit)
  }

  // ===================================================================================================================

  final class Step2[P, S](name: String, initStateFn: InitStateArg[P, S]) {
    type Next[B] = Step3[P, S, B]

    def backend[B](f: NewBackendFn[P, S, B]): Next[B] =
      new Step3(name, initStateFn, f)

    def noBackend: Next[Unit] =
      backend(_ => ())

//    /**
//     * Shortcut for:
//     *
//     * {{{
//     *   .backend[B](new B(_))
//     *   .renderBackend
//     * }}}
//     */
//    def renderBackend[B]: PSBR[P, S, B] =
//      macro CompBuilderMacros.backendAndRender[P, S, B]
  }

  // ===================================================================================================================

  final class Step3[P, S, B](name: String, initStateFn: InitStateArg[P, S], backendFn: NewBackendFn[P, S, B]) {
    type Next[C <: ChildrenArg] = Step4[P, C, S, B]
    type $ = MountedCB[P, S, B]

    // TODO Hmmm, if no children are used, should not the .propsChildren methods be removed from {Unm,M}ounted?

    def render[C <: ChildrenArg](r: RenderFn[P, S, B]): Next[C] =
      new Step4[P, C, S, B](name, initStateFn, backendFn, r)

    // No children

     def renderPS(r: ($, P, S) => raw.ReactElement): Next[ChildrenArg.None] =
       render($ => r($, $.props.runNow(), $.state.runNow()))

     def renderP(r: ($, P) => raw.ReactElement): Next[ChildrenArg.None] =
       render($ => r($, $.props.runNow()))

     def renderS(r: ($, S) => raw.ReactElement): Next[ChildrenArg.None] =
       render($ => r($, $.state.runNow()))

     def render_PS(r: (P, S) => raw.ReactElement): Next[ChildrenArg.None] =
       render($ => r($.props.runNow(), $.state.runNow()))

     def render_P(r: P => raw.ReactElement): Next[ChildrenArg.None] =
       render($ => r($.props.runNow()))

     def render_S(r: S => raw.ReactElement): Next[ChildrenArg.None] =
       render($ => r($.state.runNow()))

    // Has children

     def renderPCS(r: ($, P, PropsChildren, S) => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($, $.props.runNow(), $.propsChildren.runNow(), $.state.runNow()))

     def renderPC(r: ($, P, PropsChildren) => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($, $.props.runNow(), $.propsChildren.runNow()))

     def renderCS(r: ($, PropsChildren, S) => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($, $.propsChildren.runNow(), $.state.runNow()))

     def renderC(r: ($, PropsChildren) => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($, $.propsChildren.runNow()))

     def render_PCS(r: (P, PropsChildren, S) => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($.props.runNow(), $.propsChildren.runNow(), $.state.runNow()))

     def render_PC(r: (P, PropsChildren) => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($.props.runNow(), $.propsChildren.runNow()))

     def render_CS(r: (PropsChildren, S) => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($.propsChildren.runNow(), $.state.runNow()))

     def render_C(r: PropsChildren => raw.ReactElement): Next[ChildrenArg.Varargs] =
       render($ => r($.propsChildren.runNow()))

//    /**
//     * Use a method named `render` in the backend, automatically populating its arguments with props, state,
//     * propsChildren where needed.
//     */
//    def renderBackend: Out =
//      macro CompBuilderMacros.renderBackend[P, S, B]
  }

  // ===================================================================================================================

  final class Step4[P, C <: ChildrenArg, S, B](name       : String,
                                               initStateFn: InitStateArg[P, S],
                                               backendFn  : NewBackendFn[P, S, B],
                                               renderFn   : RenderFn[P, S, B]) {

    def spec: raw.ReactComponentSpec = {
      val spec = js.Object().asInstanceOf[raw.ReactComponentSpec]

      for (n <- Option(name))
        spec.displayName = n

      def withMounted[A](f: MountedCB[P, S, B] => A): js.ThisFunction0[raw.ReactComponent, A] =
        (rc: raw.ReactComponent) =>
          f(rc.asInstanceOf[Vars[P, S, B]].mountedCB)

      spec.render = withMounted(renderFn)

      def getInitialStateFn: js.Function =
        initStateFn match {
          case Right(fn0) => fn0
          case Left(fn) => ((rc: raw.ReactComponentElement) => {
            val js = JsComponent.BasicUnmounted[Box[P], Box[S]](rc)
            Box(fn(js.mapProps(_.a)))
          }): js.ThisFunction0[raw.ReactComponentElement, Box[S]]
        }
      spec.getInitialState = getInitialStateFn

      val componentWillMountFn: js.ThisFunction0[raw.ReactComponent, Unit] =
        (rc: raw.ReactComponent) => {
          val jMounted : JsMounted[P, S, B] = JsComponent.BasicMounted[Box[P], Box[S]](rc).addRawType[Vars[P, S, B]]
          val sMountedI: Mounted  [P, S, B] = new ScalaComponent.MountedF(jMounted)
          val sMountedC: MountedCB[P, S, B] = new ScalaComponent.MountedF(jMounted)
          val backend  : B                  = backendFn(sMountedC)
          jMounted.raw.mounted   = sMountedI
          jMounted.raw.mountedCB = sMountedC
          jMounted.raw.backend   = backend
        }
      spec.componentWillMount = componentWillMountFn

//        def onWillMountFn(f: DuringCallbackU[P, S, B] => Unit): Unit =
//          componentWillMountFn = Some(componentWillMountFn.fold(f)(g => $ => {g($); f($)}))
//        for (f <- lc.componentWillMount)
//          onWillMountFn(f(_).runNow())

//        for (f <- componentWillMountFn)
//          spec("componentWillMount") = f: ThisFunction
//
//
//        lc.getDefaultProps.flatMap(_.toJsCallback).foreach(spec("getDefaultProps") = _)
//
//        setThisFn1(                                             lc.componentWillUnmount     , "componentWillUnmount")
//        setThisFn1(                                             lc.componentDidMount        , "componentDidMount")
//        setFnPS   (ComponentWillUpdate      .apply[P, S, B, N])(lc.componentWillUpdate      , "componentWillUpdate")
//        setFnPS   (ComponentDidUpdate       .apply[P, S, B, N])(lc.componentDidUpdate       , "componentDidUpdate")
//        setFnPS   (ShouldComponentUpdate    .apply[P, S, B, N])(lc.shouldComponentUpdate    , "shouldComponentUpdate")
//        setFnP    (ComponentWillReceiveProps.apply[P, S, B, N])(lc.componentWillReceiveProps, "componentWillReceiveProps")
//
//        if (jsMixins.nonEmpty)
//          spec("mixins") = JArray(jsMixins: _*)
//
//        val spec2 = spec.asInstanceOf[ReactComponentSpec[P, S, B, N]]
//        lc.configureSpec.foreach(_(spec2).runNow())
//        spec2

      spec
    }

    def build(implicit ctorType: CtorType.Summoner[Box[P], C]): ScalaComponent[P, S, B, ctorType.CT] = {
      val rc = raw.React.createClass(spec)
      val jc = JsComponent[Box[P], C, Box[S]](rc)(ctorType).addRawType[Vars[P, S, B]](ctorType.pf)
      new ScalaComponent(jc)(ctorType.pf)
    }
  }
}