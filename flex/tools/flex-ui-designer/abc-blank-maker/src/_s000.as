package {
import com.intellij.flex.uiDesigner.flex.SpriteLoaderAsset;

import flash.display.Sprite;

import flash.geom.Rectangle;

public final class _s000 extends SpriteLoaderAsset {
  public static var bounds:Rectangle;
  public static var _swfClass:Class;

  public function _s000() {
    super(_s000, bounds);
  }

  override protected function get swfClass():Class {
    return _swfClass;
  }

  override protected function createContent():void {
    var s:Sprite = new _swfClass();
    addChild(s);
  }
}
}