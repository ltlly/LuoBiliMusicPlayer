// Render with POV-Ray 3.7, transparent PNG (+UA), at a 1.28:1 aspect ratio.
#version 3.7;
global_settings { assumed_gamma 1 max_trace_level 8 ambient_light rgb 1 }
#include "shapes3.inc"
camera { orthographic location <0,0,-25> look_at <0,0,0> right x*11.9 up y*9.3 }
background { color rgbt <0,0,0,1> }
light_source { <-7,10,-13> color rgb <1,.95,.84>*1.28 area_light <5,0,0>,<0,3,0>,5,5 adaptive 1 }
light_source { <7,3,-5> color rgb <.72,.83,1>*.58 area_light <3,0,0>,<0,3,0>,3,3 adaptive 1 }
light_source { <0,-9,2> color rgb <.65,.70,1>*.45 }
#macro Mat(C,S) texture { pigment { color srgb C } finish { ambient .14 diffuse .74 specular S roughness .035 } } #end
#declare Shell = texture { pigment { color srgb <.96,.91,.80> } normal { bumps .035 scale .025 } finish { ambient .17 diffuse .74 specular .21 roughness .04 } }
#declare Edge = Mat(<.52,.49,.44>,.18)
#declare Paper = Mat(<.96,.92,.80>,.06)
#declare Ink = Mat(<.085,.098,.13>,.19)
#declare Tape = Mat(<.20,.14,.115>,.22)
#declare Chrome = Mat(<.68,.70,.70>,.8)
#declare Red = Mat(<1,.33,.16>,.13)
#macro Plate(X1,Y1,Z1,X2,Y2,Z2,R,T)
  object { Round_Box(<X1,Y1,Z1>,<X2,Y2,Z2>,R,0) texture { T } }
#end
#macro Disk(X,Y,Z,Depth,R,T)
 cylinder { <X,Y,Z>,<X,Y,Z+Depth>,R texture {T} }
#end
#declare Cassette = union {
  Plate(-4.72,-2.97,0,4.72,2.97,.63,.18,Shell)
  Plate(-4.72,-2.97,.25,4.72,2.97,.34,.044,Edge)
  Plate(-4.59,-2.85,-.09,4.59,2.86,.16,.12,Shell)
  // Recessed paper label framed by molded plastic.
  Plate(-4.31,-1.31,-.125,4.31,2.62,-.06,.03,Edge)
  Plate(-4.23,-1.25,-.16,4.23,2.55,-.09,.034,Paper)
  box { <-4.22,1.42,-.165>,<4.22,1.69,-.156> texture {Red} }
  box { <-4.22,1.74,-.165>,<4.22,1.83,-.156> texture {Red} }
  box { <-4.22,1.89,-.165>,<4.22,1.93,-.156> texture {Red} }
  box { <-4.22,-1.22,-.166>,<4.22,-.79,-.155> texture {Ink} }
  box { <-4.0,2.04,-.164>,<2.75,2.06,-.157> texture {Edge} }
  box { <-4.0,2.26,-.164>,<2.75,2.28,-.157> texture {Edge} }
  Plate(3.13,1.99,-.183,4.02,2.44,-.155,.012,Ink)
  text { ttf "timrom.ttf" "A" .015,0 scale .43 translate <3.42,2.055,-.196> texture {Paper} }
  // Tape window, reels and molded six-tooth hubs.
  Plate(-3.63,-.64,-.231,3.63,1.23,-.158,.035,Edge)
  Plate(-3.54,-.56,-.254,3.54,1.15,-.205,.023,Ink)
  #for (I,0,1)
    #local X = -2.13+I*4.26;
    Disk(X,.29,-.27,.019,.805,Tape)
    #for (J,1,5) torus { .53+J*.044,.006 rotate x*90 translate <X,.29,-.276> texture {Edge} } #end
    Disk(X,.29,-.30,.048,.51,Shell)
    Disk(X,.29,-.32,.029,.355,Ink)
    #for (J,0,5)
      box { <-.063,.24,-.34>,<.063,.40,-.303> rotate z*(J*60+I*19) translate <X,.29,0> texture {Shell} }
    #end
  #end
  Plate(-.93,-.14,-.27,.93,.73,-.25,.009,Tape)
  box { <-.80,.03,-.278>,<.80,.44,-.2705> texture {Ink} }
  #for (J,-4,4)
    box { <J*.18-.009,-.29,-.268>,<J*.18+.009,-.19,-.253> texture {Paper} }
  #end
  // A shallow trapezoidal head well and the guide holes.
  prism { linear_sweep linear_spline 0,.115,5 < -3.78,-2.81>,<3.78,-2.81>,<3.30,-1.71>,<-3.30,-1.71>,<-3.78,-2.81>
    rotate -90*x translate <0,0,-.093> texture {Shell} }
  #for (I,0,1)
    #local X=-2.33+I*4.66;
    Disk(X,-2.44,-.216,.035,.14,Edge) Disk(X,-2.44,-.221,.014,.094,Ink)
    Disk(X*.43,-2.57,-.217,.033,.115,Ink)
    #for(J,0,7) box { <3.91+J*.065,-2.60,-.10>,<3.936+J*.065,-1.59,-.085> scale <1-I*2,1,1> texture {Edge} } #end
  #end
  #for (I,0,3)
    #local X=4.36*(1-2*mod(I,2)); #local Y=2.61*(1-2*floor(I/2));
    Disk(X,Y,-.112,.04,.15,Edge)
    difference {
      cylinder { <0,0,-.133>,<0,0,-.104>,.116 }
      box { <-.076,-.016,-.15>,<.076,.016,-.121> }
      box { <-.016,-.076,-.15>,<.016,.076,-.121> }
      rotate z*(I*31+18) translate <X,Y,0> texture {Chrome}
    }
  #end
}
object { Cassette rotate <-22,-18,-17> }
