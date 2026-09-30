import { Suspense, createContext, useContext, useRef, useMemo, useEffect } from 'react'
import { Canvas, useFrame, useThree } from '@react-three/fiber'
import { OrbitControls, Html, ContactShadows } from '@react-three/drei'
import { DoubleSide } from 'three'
import { usePrefersReducedMotion } from './ui/motion.js'
import { SCENE_ACTION, resolvesHazard, escalatesHazard } from '../lib/sceneAction.js'

/*
 * These meshes are shared with the AR overlay (see ARScene3D), which needs them
 * WITHOUT the floating drei labels: the AR view already draws its own DOM label
 * layer, and doubling it up gives every object two captions. Rather than thread a
 * flag through ten components, labels read a context that defaults to on, so this
 * scene is unchanged and AR switches them off in one place.
 *
 * Html is also relatively expensive — it mounts real DOM per label and forces a
 * layout sync each frame — which is affordable in a static viewer and not on a
 * mid-range phone already running the camera, a hand tracker and a 3D pass.
 */
const ShowLabelsContext = createContext(true)

/*
 * The worker's chosen action, so the scene can play it out.
 *
 * Delivered by context rather than by props because the things that need to react
 * are scattered — the fire, the worker, the conveyor — and threading a prop through
 * every mesh to reach three of them would touch every component in this file for no
 * benefit. Null means no answer yet: the scene sits at rest.
 */
const SceneActionContext = createContext(null)

function useSceneAction() {
  return useContext(SceneActionContext)
}

/**
 * Ease a value toward a target, frame-rate independently.
 *
 * `1 - pow(k, dt)` rather than `dt * k`: the naive form changes speed with frame
 * rate, so the same animation would run visibly faster on a 120Hz phone than on a
 * 30Hz one. Used for every transition in this file so they all settle together.
 */
function approach(current, target, dt, speed = 3) {
  return current + (target - current) * (1 - Math.pow(0.5, dt * speed))
}

/*
 * Reduced motion draws a line through the MIDDLE of this scene, not around it.
 *
 * There are two kinds of movement here and they deserve opposite treatment:
 *
 *   Decorative — flame flicker, the walking bob, beacon blink, jet jitter. Pure
 *     ornament. Switched off entirely.
 *   Consequential — the fire going out, the worker leaving, the padlock going on,
 *     the conveyor stopping. This is the ANSWER being shown. Hiding it would remove
 *     the feature rather than calm it, and would leave a worker who set the
 *     preference with the static diagram everyone else just stopped having.
 *
 * So consequential transitions still happen, they simply arrive at once instead of
 * easing. This returns the easing rate to use: normal, or high enough that
 * `approach` lands on its target within a single frame.
 */
function useMotionSpeed(base) {
  return usePrefersReducedMotion() ? 1000 : base
}

export function HideMeshLabels({ children }) {
  return <ShowLabelsContext.Provider value={false}>{children}</ShowLabelsContext.Provider>
}

function Label(props) {
  return useContext(ShowLabelsContext) ? <Html {...props} /> : null
}

/* -------------------- FLOOR -------------------- */

function Floor() {
  return (
    /* Widened from 14x10 so the three added scenes do not overhang the edge, and
       given a rough, near-matte material: a default meshStandardMaterial has a
       faint specular sheen that made a mine floor look like polished stone. */
    <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, -1.5, 0]}>
      <planeGeometry args={[20, 14]} />
      <meshStandardMaterial color="#465255" roughness={0.98} metalness={0} />
    </mesh>
  )
}

/* -------------------- WORKER -------------------- */

function Worker({ position = [-2.5, -0.5, 0] }) {
  return (
    <group position={position}>
      {[-1, 1].map(side => <group key={side}>
        <mesh position={[side * .48, -.05, 0]} rotation={[0, 0, side * .15]}>
          <capsuleGeometry args={[.13, .65, 4, 10]} /><meshStandardMaterial color="#53676b" roughness={.85} />
        </mesh>
        <mesh position={[side * .54, -.47, .02]}><sphereGeometry args={[.15, 12, 10]} /><meshStandardMaterial color="#d4bc81" /></mesh>
        <mesh position={[side * .18, -1.18, .08]}><boxGeometry args={[.26, .18, .44]} /><meshStandardMaterial color="#222b2f" /></mesh>
        <mesh position={[side * .2, .15, .301]}><boxGeometry args={[.075, .7, .015]} /><meshStandardMaterial color="#e6edbe" /></mesh>
      </group>)}
      <mesh position={[0, -.18, .31]}><boxGeometry args={[.7, .09, .018]} /><meshStandardMaterial color="#e6edbe" /></mesh>
      <mesh position={[0, 1.04, .035]}><cylinderGeometry args={[.41, .41, .045, 24]} /><meshStandardMaterial color="#f5c518" /></mesh>
      <mesh position={[0, .91, .275]}><boxGeometry args={[.42, .12, .08]} /><meshStandardMaterial color="#6cbbcc" metalness={.15} roughness={.3} /></mesh>
      {/* Body */}
      <mesh position={[0, 0, 0]}>
        <boxGeometry args={[0.7, 1.2, 0.45]} />
        <meshStandardMaterial color="#263238" />
      </mesh>

      {/* Safety vest */}
      <mesh position={[0, 0.05, 0.25]}>
        <boxGeometry args={[0.72, 0.9, 0.08]} />
        <meshStandardMaterial color="#ff9800" />
      </mesh>

      {/* Head */}
      <mesh position={[0, 0.9, 0]}>
        <sphereGeometry args={[0.3, 20, 20]} />
        <meshStandardMaterial color="#c68642" />
      </mesh>

      {/* Helmet */}
      <mesh position={[0, 1.12, 0]}>
        <sphereGeometry args={[0.36, 20, 12]} />
        <meshStandardMaterial color="#f5c518" />
      </mesh>

      {/* Left leg */}
      <mesh position={[-0.18, -0.8, 0]}>
        <boxGeometry args={[0.2, 0.8, 0.25]} />
        <meshStandardMaterial color="#17202a" />
      </mesh>

      {/* Right leg */}
      <mesh position={[0.18, -0.8, 0]}>
        <boxGeometry args={[0.2, 0.8, 0.25]} />
        <meshStandardMaterial color="#17202a" />
      </mesh>

      <Label position={[0, 1.65, 0]} center>
        <div
          style={{
            background: '#1565c0',
            color: 'white',
            padding: '4px 8px',
            borderRadius: '4px',
            fontSize: '11px',
            fontWeight: 'bold',
            whiteSpace: 'nowrap',
          }}
        >
          WORKER
        </div>
      </Label>
    </group>
  )
}

/* -------------------- FIRE -------------------- */

function Fire({ position = [2, -0.5, 0] }) {
  const outer = useRef(null)
  const inner = useRef(null)
  const glow = useRef(null)
  const group = useRef(null)
  const reduced = usePrefersReducedMotion()
  const dieSpeed = useMotionSpeed(2.2)
  const action = useSceneAction()

  /*
   * How big the fire is, as a multiplier that eases toward its target.
   *
   * This is the change the whole reactive-scene effort was about: choose the CO2
   * extinguisher and the fire goes out, in front of you. Choose to open the door on
   * a hot fire and it grows. Previously both showed an identical, unchanging flame.
   */
  const size = useRef(1)

  /*
   * Flicker. A static cone reads as an orange traffic bollard; the irregular
   * motion is most of what makes it read as fire at all.
   *
   * Two sine waves at unrelated frequencies rather than one, because a single
   * sine is visibly periodic and starts to look mechanical after a few seconds.
   * The light flickers with it, so the flame appears to be the thing lighting
   * the scene rather than a lit object sitting in it.
   */
  useFrame((state, delta) => {
    // Ease toward the outcome. Out entirely when the hazard was correctly dealt
    // with, half again as big when the answer made things worse.
    const target = resolvesHazard(action) ? 0 : escalatesHazard(action) ? 1.5 : 1
    // Clamped delta: returning to a backgrounded tab delivers one enormous frame,
    // which would otherwise snap the fire to its target in a single jump.
    size.current = approach(size.current, target, Math.min(delta, 0.1), dieSpeed)
    const s = size.current

    if (group.current) {
      group.current.scale.setScalar(Math.max(0.0001, s))
      // Hide completely once out, so no sliver of geometry remains.
      group.current.visible = s > 0.02
    }

    const t = state.clock.elapsedTime
    // Flicker gets more violent as the fire grows, and stops as it dies.
    const a = reduced ? 0 : (Math.sin(t * 9) * 0.5 + Math.sin(t * 14.7) * 0.5) * s
    if (outer.current) outer.current.scale.set(1 + a * 0.05, 1 + a * 0.11, 1 + a * 0.05)
    if (inner.current) inner.current.scale.set(1 - a * 0.06, 1 + a * 0.16, 1 - a * 0.06)
    // The light dies with the flame, which is what sells it as the light source.
    if (glow.current) glow.current.intensity = Math.max(0, (2.4 + a * 0.9) * s)
  })

  return (
    <group ref={group} position={position}>
      {/*
        The flame lights its own surroundings. Warm, short-range, and the single
        biggest contributor to the scene looking lit rather than flat.
      */}
      <pointLight ref={glow} color="#ff7a1a" intensity={2.4} distance={7} decay={2} position={[0, 0.6, 0]} />

      {/* Outer flame */}
      <mesh ref={outer}>
        <coneGeometry args={[0.7, 1.8, 16]} />
        <meshStandardMaterial
          color="#ff741f"
          emissive="#ff5000"
          emissiveIntensity={1.3}
          transparent opacity={0.68}
          roughness={1}
        />
      </mesh>

      {/* Inner flame */}
      <mesh ref={inner} position={[0, 0.35, 0]}>
        <coneGeometry args={[0.38, 1.2, 16]} />
        <meshStandardMaterial
          color="#ffb300"
          emissive="#ff6f00"
          emissiveIntensity={1}
          roughness={1}
        />
      </mesh>

      {[0, 1, 2, 3, 4, 5].map(i => <mesh key={i}
        position={[Math.cos(i * 2.4) * .38, -.22 + (i % 3) * .1, Math.sin(i * 2.4) * .38]}
        rotation={[.13 * Math.sin(i), 0, .18 * Math.cos(i)]}>
        <coneGeometry args={[.24, 1 + (i % 3) * .3, 8]} />
        <meshStandardMaterial color="#ffc24b" emissive="#ff8300" emissiveIntensity={1.2} transparent opacity={.8} />
      </mesh>)}

      <Label position={[0, 1.5, 0]} center>
        <div
          style={{
            background: '#d32f2f',
            color: 'white',
            padding: '5px 10px',
            borderRadius: '4px',
            fontWeight: 'bold',
            fontSize: '12px',
          }}
        >
          FIRE
        </div>
      </Label>
    </group>
  )
}

/* -------------------- FIRE EXTINGUISHER -------------------- */

function FireExtinguisher({ position = [3.4, -0.7, 0] }) {
  return (
    <group position={position}>
      <mesh>
        <cylinderGeometry args={[0.28, 0.32, 1.3, 20]} />
        <meshStandardMaterial color="#c62828" />
      </mesh>

      <mesh position={[0, 0.75, 0]}>
        <cylinderGeometry args={[0.15, 0.15, 0.2, 16]} />
        <meshStandardMaterial color="#222222" />
      </mesh>

      <mesh position={[0.15, 0.9, 0]} rotation={[0, 0, -0.5]}>
        <boxGeometry args={[0.35, 0.08, 0.08]} />
        <meshStandardMaterial color="#222222" />
      </mesh>

      <Label position={[0, 1.1, 0]} center>
        <div
          style={{
            background: '#c62828',
            color: 'white',
            padding: '4px 7px',
            borderRadius: '4px',
            fontSize: '10px',
            fontWeight: 'bold',
            whiteSpace: 'nowrap',
          }}
        >
          EXTINGUISHER
        </div>
      </Label>
    </group>
  )
}

/* -------------------- ELECTRICAL PANEL -------------------- */

function ElectricalPanel({ position = [2, 0, 0] }) {
  return (
    <group position={position}>
      {/* Main panel */}
      <mesh>
        <boxGeometry args={[2.3, 2.6, 0.6]} />
        <meshStandardMaterial color="#607d8b" />
      </mesh>

      {/* Front */}
      <mesh position={[0, 0, 0.33]}>
        <boxGeometry args={[1.8, 2.1, 0.06]} />
        <meshStandardMaterial color="#263238" />
      </mesh>

      {/* Buttons */}
      <mesh position={[-0.5, 0.4, 0.38]}>
        <sphereGeometry args={[0.1, 16, 16]} />
        <meshStandardMaterial
          color="#4caf50"
          emissive="#1b5e20"
          emissiveIntensity={1}
        />
      </mesh>

      <mesh position={[0, 0.4, 0.38]}>
        <sphereGeometry args={[0.1, 16, 16]} />
        <meshStandardMaterial
          color="#f44336"
          emissive="#b71c1c"
          emissiveIntensity={1}
        />
      </mesh>

      <mesh position={[0.5, 0.4, 0.38]}>
        <sphereGeometry args={[0.1, 16, 16]} />
        <meshStandardMaterial
          color="#4caf50"
          emissive="#1b5e20"
          emissiveIntensity={1}
        />
      </mesh>

      {/* Exposed cable */}
      <mesh position={[1.2, -0.8, 0.4]} rotation={[0, 0, 0.3]}>
        <cylinderGeometry args={[0.06, 0.06, 2.5, 12]} />
        <meshStandardMaterial color="#111111" />
      </mesh>

      <Label position={[0, 1.7, 0]} center>
        <div
          style={{
            background: '#f9a825',
            color: '#111',
            padding: '5px 10px',
            borderRadius: '4px',
            fontWeight: 'bold',
            fontSize: '11px',
          }}
        >
          HIGH VOLTAGE
        </div>
      </Label>
    </group>
  )
}

/* -------------------- MACHINERY -------------------- */

function Machinery({ position = [2, -0.2, 0] }) {
  return (
    <group position={position}>
      {/* Machine body */}
      <mesh>
        <boxGeometry args={[2.8, 1.8, 1.8]} />
        <meshStandardMaterial color="#455a64" />
      </mesh>

      {/* Top cylinder */}
      <mesh position={[0, 1.15, 0]}>
        <cylinderGeometry args={[0.65, 0.65, 0.4, 32]} />
        <meshStandardMaterial color="#90a4ae" />
      </mesh>

      {/* Central shaft */}
      <mesh
        position={[0, 1.15, 0.3]}
        rotation={[Math.PI / 2, 0, 0]}
      >
        <cylinderGeometry args={[0.32, 0.32, 0.12, 32]} />
        <meshStandardMaterial color="#111111" />
      </mesh>

      {/* Safety guard */}
      <mesh position={[0, 0.2, 1]}>
        <boxGeometry args={[2.2, 1.2, 0.08]} />
        <meshStandardMaterial
          color="#f9a825"
          transparent
          opacity={0.45}
        />
      </mesh>

      <Label position={[0, 2, 0]} center>
        <div
          style={{
            background: '#ef6c00',
            color: 'white',
            padding: '5px 10px',
            borderRadius: '4px',
            fontWeight: 'bold',
            fontSize: '11px',
            whiteSpace: 'nowrap',
          }}
        >
          HYDRAULIC PRESS
        </div>
      </Label>
    </group>
  )
}

/* -------------------- MINE -------------------- */

function MineTunnel({ position = [0, 0, 0] }) {
  return (
    <group position={position}>
      {/* Tunnel back wall */}
      <mesh position={[2, 0.5, -1]}>
        <boxGeometry args={[4, 4, 0.3]} />
        <meshStandardMaterial color="#3e2723" />
      </mesh>

      {/* Tunnel left wall */}
      <mesh position={[0, 0.5, 0]}>
        <boxGeometry args={[0.3, 4, 2.5]} />
        <meshStandardMaterial color="#4e342e" />
      </mesh>

      {/* Tunnel right wall */}
      <mesh position={[4, 0.5, 0]}>
        <boxGeometry args={[0.3, 4, 2.5]} />
        <meshStandardMaterial color="#4e342e" />
      </mesh>

      {/* Tunnel roof */}
      <mesh position={[2, 2.4, 0]}>
        <boxGeometry args={[4, 0.3, 2.5]} />
        <meshStandardMaterial color="#3e2723" />
      </mesh>

      {/* Tunnel floor */}
      <mesh position={[2, -1.3, 0]}>
        <boxGeometry args={[4, 0.3, 2.5]} />
        <meshStandardMaterial color="#5d4037" />
      </mesh>

      {/* Support beams */}
      <mesh position={[1, 0.5, 0]}>
        <boxGeometry args={[0.18, 3.5, 0.18]} />
        <meshStandardMaterial color="#8d6e63" />
      </mesh>

      <mesh position={[3, 0.5, 0]}>
        <boxGeometry args={[0.18, 3.5, 0.18]} />
        <meshStandardMaterial color="#8d6e63" />
      </mesh>

      <Label position={[2, 2.9, 0]} center>
        <div
          style={{
            background: '#f9a825',
            color: '#111',
            padding: '5px 10px',
            borderRadius: '4px',
            fontWeight: 'bold',
            fontSize: '11px',
            whiteSpace: 'nowrap',
          }}
        >
          MINE / CONFINED SPACE
        </div>
      </Label>
    </group>
  )
}

/* -------------------- GAS DETECTOR -------------------- */

function GasDetector({ position = [3, -0.3, 0.7] }) {
  return (
    <group position={position}>
      <mesh>
        <boxGeometry args={[0.6, 1, 0.25]} />
        <meshStandardMaterial color="#263238" />
      </mesh>

      <mesh position={[0, 0.15, 0.14]}>
        <boxGeometry args={[0.35, 0.25, 0.04]} />
        <meshStandardMaterial
          color="#4caf50"
          emissive="#1b5e20"
          emissiveIntensity={1}
        />
      </mesh>

      <Label position={[0, 0.8, 0]} center>
        <div
          style={{
            background: '#2e7d32',
            color: 'white',
            padding: '4px 7px',
            borderRadius: '4px',
            fontSize: '10px',
            fontWeight: 'bold',
            whiteSpace: 'nowrap',
          }}
        >
          GAS DETECTOR
        </div>
      </Label>
    </group>
  )
}

/* -------------------- DUST MACHINE -------------------- */

function DustMachine({ position = [2, -0.1, 0] }) {
  return (
    <group position={position}>
      <mesh>
        <boxGeometry args={[2.5, 2, 1.6]} />
        <meshStandardMaterial color="#6d4c41" />
      </mesh>

      <mesh position={[0, 1.3, 0]}>
        <cylinderGeometry args={[0.6, 0.6, 0.8, 20]} />
        <meshStandardMaterial color="#795548" />
      </mesh>

      {/* Dust particles */}
      <mesh position={[-0.5, 2, 0]}>
        <sphereGeometry args={[0.14, 12, 12]} />
        <meshStandardMaterial
          color="#d7ccc8"
          transparent
          opacity={0.65}
        />
      </mesh>

      <mesh position={[0, 2.2, 0.2]}>
        <sphereGeometry args={[0.12, 12, 12]} />
        <meshStandardMaterial
          color="#d7ccc8"
          transparent
          opacity={0.65}
        />
      </mesh>

      <mesh position={[0.5, 1.9, 0]}>
        <sphereGeometry args={[0.13, 12, 12]} />
        <meshStandardMaterial
          color="#d7ccc8"
          transparent
          opacity={0.65}
        />
      </mesh>

      <Label position={[0, 2.6, 0]} center>
        <div
          style={{
            background: '#795548',
            color: 'white',
            padding: '5px 10px',
            borderRadius: '4px',
            fontWeight: 'bold',
            fontSize: '11px',
          }}
        >
          DUST HAZARD
        </div>
      </Label>
    </group>
  )
}

/* -------------------- WAREHOUSE -------------------- */

function Warehouse({ position = [2, -0.5, 0] }) {
  return (
    <group position={position}>
      {/* Forklift body */}
      <mesh>
        <boxGeometry args={[2.2, 1, 1.2]} />
        <meshStandardMaterial color="#f9a825" />
      </mesh>

      {/* Mast */}
      <mesh position={[1, 1, 0]}>
        <boxGeometry args={[0.15, 2.5, 0.15]} />
        <meshStandardMaterial color="#455a64" />
      </mesh>

      {/* Fork */}
      <mesh position={[1.6, -0.15, 0.35]}>
        <boxGeometry args={[1.4, 0.12, 0.12]} />
        <meshStandardMaterial color="#78909c" />
      </mesh>

      <mesh position={[1.6, -0.15, -0.35]}>
        <boxGeometry args={[1.4, 0.12, 0.12]} />
        <meshStandardMaterial color="#78909c" />
      </mesh>

      {/* Cargo */}
      <mesh position={[-0.2, 0.7, 0]}>
        <boxGeometry args={[1, 0.8, 1]} />
        <meshStandardMaterial color="#8d6e63" />
      </mesh>

      <Label position={[0, 1.8, 0]} center>
        <div
          style={{
            background: '#f9a825',
            color: '#111',
            padding: '5px 10px',
            borderRadius: '4px',
            fontWeight: 'bold',
            fontSize: '11px',
          }}
        >
          FORKLIFT
        </div>
      </Label>
    </group>
  )
}

/* -------------------- ACTION EFFECTS -------------------- */

/*
 * These render only while an action is playing. Each is deliberately literal: a
 * worker should be able to say what happened without reading anything, because the
 * point of the scene reacting is that the consequence is visible rather than
 * described.
 */

/** A cone of discharged agent, from the extinguisher toward the fire. */
function AgentJet() {
  const ref = useRef(null)
  const reduced = usePrefersReducedMotion()

  useFrame((state, delta) => {
    const m = ref.current
    if (!m) return
    // Grows out along its length, so it reads as being sprayed rather than
    // appearing all at once.
    const t = reduced ? 1 : Math.min(1, (m.userData.age = (m.userData.age || 0) + delta) / 0.5)
    m.scale.set(t, 1, t)
    if (!reduced) {
      // Slight jitter: a discharge is not a smooth cone.
      m.rotation.z = Math.PI / 2 + Math.sin(state.clock.elapsedTime * 22) * 0.03
    }
  })

  return (
    /* Positioned between the extinguisher (x 3.4) and the fire (x 2), pointing
       along -X. Rotated so the cone's axis lies horizontal. */
    <mesh ref={ref} position={[2.75, -0.35, 0]} rotation={[0, 0, Math.PI / 2]}>
      <coneGeometry args={[0.42, 1.5, 18, 1, true]} />
      <meshStandardMaterial
        color="#F2F1ED"
        emissive="#F2F1ED"
        emissiveIntensity={0.3}
        transparent
        opacity={0.62}
        side={DoubleSide}
        depthWrite={false}
      />
    </mesh>
  )
}

/** Water mist onto a dust source. */
function WaterMist() {
  const drops = useRef([])
  const reduced = usePrefersReducedMotion()

  const seeds = useMemo(
    () => Array.from({ length: 9 }, (_, i) => ({ x: 1.2 + (i % 3) * 0.5, z: -0.4 + Math.floor(i / 3) * 0.4, o: i * 0.31 })),
    [],
  )

  useFrame((state, delta) => {
    if (reduced) return
    for (const d of drops.current) {
      if (!d) continue
      d.position.y -= delta * 1.6
      if (d.position.y < -1.4) d.position.y = 1.5
    }
  })

  return (
    <group>
      {seeds.map((s, i) => (
        <mesh
          key={s.o}
          ref={(el) => {
            drops.current[i] = el
          }}
          position={[s.x, 1.5 - s.o, s.z]}
        >
          <sphereGeometry args={[0.055, 8, 8]} />
          <meshStandardMaterial color="#8fc4e8" transparent opacity={0.75} depthWrite={false} />
        </mesh>
      ))}
    </group>
  )
}

/** Flashing beacon: the alarm has been raised. */
function Beacon() {
  const light = useRef(null)
  const lens = useRef(null)
  const reduced = usePrefersReducedMotion()

  useFrame((state) => {
    // A square wave, not a sine: real beacons blink, they do not breathe.
    const on = reduced ? 1 : Math.sin(state.clock.elapsedTime * 7) > 0 ? 1 : 0.12
    if (light.current) light.current.intensity = 3.2 * on
    if (lens.current) lens.current.material.emissiveIntensity = 0.35 + on * 1.4
  })

  return (
    <group position={[0, 2.6, -1.6]}>
      <pointLight ref={light} color="#FFB020" intensity={3.2} distance={11} decay={2} />
      <mesh ref={lens}>
        <sphereGeometry args={[0.28, 16, 12]} />
        <meshStandardMaterial color="#FFB020" emissive="#FFB020" emissiveIntensity={1} />
      </mesh>
      <mesh position={[0, -0.3, 0]}>
        <cylinderGeometry args={[0.2, 0.24, 0.22, 14]} />
        <meshStandardMaterial color="#3a3f45" roughness={0.5} metalness={0.6} />
      </mesh>
    </group>
  )
}

/** A padlock, to show the isolation actually went on. */
function Padlock({ position = [2, 1.4, 0.5] }) {
  const ref = useRef(null)
  const reduced = usePrefersReducedMotion()

  useFrame((state, delta) => {
    const g = ref.current
    if (!g) return
    // Drops into place and settles, so the moment of locking is visible. Under
    // reduced motion it is simply there — the lock still appears, it just does not
    // travel.
    const age = (g.userData.age = (g.userData.age || 0) + delta)
    const t = reduced ? 1 : Math.min(1, age / 0.45)
    g.position.y = position[1] + (1 - t) * 0.7
    g.scale.setScalar(0.6 + t * 0.4)
  })

  return (
    <group ref={ref} position={position}>
      <mesh>
        <boxGeometry args={[0.34, 0.28, 0.14]} />
        <meshStandardMaterial color="#2E7D4F" roughness={0.4} metalness={0.5} />
      </mesh>
      <mesh position={[0, 0.22, 0]} rotation={[Math.PI / 2, 0, 0]}>
        <torusGeometry args={[0.11, 0.035, 8, 16, Math.PI]} />
        <meshStandardMaterial color="#c8ccd0" roughness={0.35} metalness={0.9} />
      </mesh>
    </group>
  )
}

/** A respirator on the worker's face. */
function RespiratorOnWorker() {
  const ref = useRef(null)
  const reduced = usePrefersReducedMotion()

  useFrame((state, delta) => {
    const m = ref.current
    if (!m) return
    const t = reduced ? 1 : Math.min(1, (m.userData.age = (m.userData.age || 0) + delta) / 0.3)
    m.scale.setScalar(t)
  })

  return (
    <mesh ref={ref} position={[0, 0.92, 0.3]}>
      <sphereGeometry args={[0.19, 14, 12]} />
      <meshStandardMaterial color="#2b3034" roughness={0.55} metalness={0.2} />
    </mesh>
  )
}

/* -------------------- ROOF & STRATA -------------------- */

/*
 * Three modules shipped with no scene of their own and fell through to the
 * default, which returned a fire. A worker opening the Working at Height briefing
 * was shown a fire. These three exist to fix that, and each is modelled around the
 * one thing its module is actually teaching.
 */

/** Layered roof with bolts, a support prop, and the slab that already fell. */
function RoofStrata({ position = [1.5, 0, 0] }) {
  return (
    <group position={position}>
      {/*
        Strata drawn as separate beds rather than one slab, because the bedding
        planes ARE the hazard being taught — rock parts along them, and a worker
        needs to recognise the banding overhead.
      */}
      {[
        { y: 2.6, h: 0.34, c: '#5b5147' },
        { y: 2.26, h: 0.18, c: '#6d6154' },
        { y: 2.06, h: 0.26, c: '#4f463d' },
        { y: 1.84, h: 0.14, c: '#7a6c5d' },
      ].map((bed) => (
        <mesh key={bed.y} position={[0, bed.y, 0]}>
          <boxGeometry args={[5.2, bed.h, 3.4]} />
          <meshStandardMaterial color={bed.c} roughness={0.95} metalness={0.02} />
        </mesh>
      ))}

      {/* Roof bolts — the control measure, and what the drill asks about. */}
      {[-1.6, -0.55, 0.55, 1.6].map((x) => (
        <group key={x} position={[x, 1.4, 0.4]}>
          <mesh>
            <cylinderGeometry args={[0.055, 0.055, 0.9, 10]} />
            <meshStandardMaterial color="#9aa0a6" roughness={0.4} metalness={0.85} />
          </mesh>
          {/* Bearing plate */}
          <mesh position={[0, -0.47, 0]}>
            <boxGeometry args={[0.3, 0.05, 0.3]} />
            <meshStandardMaterial color="#c8ccd0" roughness={0.35} metalness={0.9} />
          </mesh>
        </group>
      ))}

      {/* Timber prop, leaning slightly because nothing underground is plumb. */}
      <mesh position={[-2.1, 0.15, 0.8]} rotation={[0, 0, 0.06]}>
        <cylinderGeometry args={[0.16, 0.18, 3.1, 12]} />
        <meshStandardMaterial color="#8d6e4f" roughness={0.9} />
      </mesh>

      {/* The slab that already came down. The reason the module exists. */}
      <mesh position={[0.9, -1.28, 0.9]} rotation={[0.1, 0.5, 0.16]}>
        <boxGeometry args={[1.5, 0.3, 1.1]} />
        <meshStandardMaterial color="#443c34" roughness={1} />
      </mesh>
      <mesh position={[1.7, -1.36, 0.4]} rotation={[0.3, 1.1, 0.1]}>
        <boxGeometry args={[0.7, 0.22, 0.6]} />
        <meshStandardMaterial color="#4d443b" roughness={1} />
      </mesh>

      <Label position={[0, 3.1, 0]} center>
        <div
          style={{
            background: '#8d6e4f',
            color: 'white',
            padding: '4px 8px',
            borderRadius: '4px',
            fontSize: '10px',
            fontWeight: 'bold',
            whiteSpace: 'nowrap',
          }}
        >
          ROOF / STRATA
        </div>
      </Label>
    </group>
  )
}

/* -------------------- WORKING AT HEIGHT -------------------- */

/** Scaffold with a guard rail on one side and an open edge on the other. */
function WorkingAtHeight({ position = [1.8, 0, 0] }) {
  return (
    <group position={position}>
      {/* Platform */}
      <mesh position={[0, 0.5, 0]}>
        <boxGeometry args={[3, 0.14, 1.9]} />
        <meshStandardMaterial color="#a98a5c" roughness={0.85} />
      </mesh>

      {/* Legs and bracing */}
      {[
        [-1.35, -0.8],
        [1.35, -0.8],
        [-1.35, 0.8],
        [1.35, 0.8],
      ].map(([x, z]) => (
        <mesh key={`${x}:${z}`} position={[x, -0.5, z]}>
          <cylinderGeometry args={[0.06, 0.06, 2, 10]} />
          <meshStandardMaterial color="#7f8996" roughness={0.4} metalness={0.8} />
        </mesh>
      ))}
      <mesh position={[0, -0.5, -0.8]} rotation={[0, 0, Math.PI / 2.6]}>
        <cylinderGeometry args={[0.04, 0.04, 3.1, 8]} />
        <meshStandardMaterial color="#7f8996" roughness={0.45} metalness={0.75} />
      </mesh>

      {/*
        Guard rail on the far side only. The near edge is deliberately open — the
        module teaches recognising an unprotected edge, so the scene has to contain
        one rather than showing a compliant platform.
      */}
      {[1.05, 0.72].map((y) => (
        <mesh key={y} position={[0, y, -0.9]}>
          <cylinderGeometry args={[0.035, 0.035, 3, 8]} />
          <meshStandardMaterial color="#FFB020" roughness={0.5} metalness={0.3} />
        </mesh>
      ))}
      {[-1.45, 1.45].map((x) => (
        <mesh key={x} position={[x, 0.85, -0.9]}>
          <cylinderGeometry args={[0.04, 0.04, 0.75, 8]} />
          <meshStandardMaterial color="#FFB020" roughness={0.5} metalness={0.3} />
        </mesh>
      ))}

      {/* Anchor point for a harness lanyard — the control measure. */}
      <mesh position={[1.2, 1.5, 0.7]}>
        <cylinderGeometry args={[0.05, 0.05, 1.9, 10]} />
        <meshStandardMaterial color="#9aa0a6" roughness={0.4} metalness={0.85} />
      </mesh>
      <mesh position={[1.2, 2.4, 0.7]} rotation={[Math.PI / 2, 0, 0]}>
        <torusGeometry args={[0.16, 0.045, 8, 18]} />
        <meshStandardMaterial color="#2E7D4F" roughness={0.35} metalness={0.7} />
      </mesh>

      {/* Ladder to the platform */}
      <group position={[-1.9, -0.35, 0.55]} rotation={[0, 0, -0.2]}>
        {[-0.22, 0.22].map((x) => (
          <mesh key={x} position={[x, 0, 0]}>
            <boxGeometry args={[0.07, 2.4, 0.07]} />
            <meshStandardMaterial color="#b9c0c7" roughness={0.4} metalness={0.7} />
          </mesh>
        ))}
        {[-0.9, -0.45, 0, 0.45, 0.9].map((y) => (
          <mesh key={y} position={[0, y, 0]}>
            <boxGeometry args={[0.5, 0.05, 0.05]} />
            <meshStandardMaterial color="#b9c0c7" roughness={0.4} metalness={0.7} />
          </mesh>
        ))}
      </group>

      <Label position={[0, 2.85, 0]} center>
        <div
          style={{
            background: '#FFB020',
            color: '#1C1F22',
            padding: '4px 8px',
            borderRadius: '4px',
            fontSize: '10px',
            fontWeight: 'bold',
            whiteSpace: 'nowrap',
          }}
        >
          OPEN EDGE
        </div>
      </Label>
    </group>
  )
}

/* -------------------- MINE HAULAGE -------------------- */

/** Running conveyor with a walkway crossing. The belt actually moves. */
function MineHaulage({ position = [1.6, -0.4, 0] }) {
  const rollers = useRef([])
  const lumps = useRef([])
  const reduced = usePrefersReducedMotion()
  const action = useSceneAction()

  /*
   * Correctly isolating the conveyor stops it. This is the visible payoff for the
   * lockout answer in the haulage module — the belt was running a second ago and now
   * it is not, which is the whole thing the module is trying to teach.
   */
  const stopped = resolvesHazard(action) && action?.kind === SCENE_ACTION.ISOLATE
  const speed = useRef(1)
  const spinDown = useMotionSpeed(2.5)

  // Fixed pseudo-random offsets so the coal does not sit in a suspiciously even
  // row, but is stable across re-renders.
  const lumpSeeds = useMemo(
    () => [0, 0.9, 1.7, 2.6, 3.4, 4.2].map((s, i) => ({ s, z: ((i * 37) % 7) / 20 - 0.175 })),
    [],
  )

  useFrame((state, delta) => {
    // Spin down rather than cutting dead: a loaded belt coasts, and the deceleration
    // is what makes it read as having been stopped rather than switched off in a cut.
    speed.current = approach(speed.current, stopped ? 0 : 1, Math.min(delta, 0.1), spinDown)
    const v = speed.current
    if (reduced || v < 0.01) return
    delta *= v
    for (const r of rollers.current) if (r) r.rotation.z -= delta * 3.2
    /*
     * Coal travels along the belt and wraps. Moving real lumps rather than
     * scrolling a texture means the motion is unmistakable at any angle, and it
     * is what makes the machine read as RUNNING — which is the whole point of a
     * module about not crossing a live conveyor.
     */
    for (const m of lumps.current) {
      if (!m) continue
      m.position.x += delta * 1.5
      if (m.position.x > 2.6) m.position.x = -2.6
      m.rotation.z -= delta * 1.1
    }
  })

  return (
    <group position={position}>
      {/* Belt bed */}
      <mesh position={[0, 0.6, 0]}>
        <boxGeometry args={[5.4, 0.1, 1.1]} />
        <meshStandardMaterial color="#2a2d31" roughness={0.95} />
      </mesh>

      {/* Rollers */}
      {[-2.4, -1.2, 0, 1.2, 2.4].map((x, i) => (
        <mesh
          key={x}
          ref={(el) => {
            rollers.current[i] = el
          }}
          position={[x, 0.52, 0]}
          rotation={[Math.PI / 2, 0, 0]}
        >
          <cylinderGeometry args={[0.17, 0.17, 1.2, 14]} />
          <meshStandardMaterial color="#8a9098" roughness={0.45} metalness={0.8} />
        </mesh>
      ))}

      {/* Coal on the belt */}
      {lumpSeeds.map(({ s, z }, i) => (
        <mesh
          key={s}
          ref={(el) => {
            lumps.current[i] = el
          }}
          position={[s - 2.6, 0.73, z]}
          rotation={[0.3, s, 0.2]}
        >
          <dodecahedronGeometry args={[0.17, 0]} />
          <meshStandardMaterial color="#1b1b1d" roughness={1} />
        </mesh>
      ))}

      {/* Frame legs */}
      {[-2.2, 0, 2.2].map((x) => (
        <mesh key={x} position={[x, 0.05, 0]}>
          <boxGeometry args={[0.12, 1, 0.12]} />
          <meshStandardMaterial color="#6f767e" roughness={0.5} metalness={0.7} />
        </mesh>
      ))}

      {/* Guard along the walkway side, and a marked crossing point. */}
      <mesh position={[0, 0.95, 0.72]}>
        <boxGeometry args={[5.4, 0.5, 0.06]} />
        <meshStandardMaterial color="#FFB020" roughness={0.6} metalness={0.2} />
      </mesh>
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, -0.44, 1.55]}>
        <planeGeometry args={[1.5, 1.3]} />
        <meshStandardMaterial color="#2E7D4F" roughness={0.9} />
      </mesh>

      <Label position={[0, 1.55, 0]} center>
        <div
          style={{
            background: '#FFB020',
            color: '#1C1F22',
            padding: '4px 8px',
            borderRadius: '4px',
            fontSize: '10px',
            fontWeight: 'bold',
            whiteSpace: 'nowrap',
          }}
        >
          CONVEYOR RUNNING
        </div>
      </Label>
    </group>
  )
}

/*
 * Shared with the AR overlay. Exported rather than copied so there is exactly one
 * definition of what a fire or an extinguisher looks like — two would drift apart,
 * and a worker who learns an object in the 3D briefing has to recognise the same
 * object in the field.
 *
 * Each takes a `position` defaulting to the coordinates this scene has always used,
 * so the viewer below renders them prop-less and unchanged. AR passes its own.
 */
export {
  Worker,
  Fire,
  FireExtinguisher,
  ElectricalPanel,
  Machinery,
  MineTunnel,
  GasDetector,
  DustMachine,
  Warehouse,
  RoofStrata,
  WorkingAtHeight,
  MineHaulage,
}

/* -------------------- SCENE SELECTOR -------------------- */

function ScenarioObjects({ scenarioId }) {
  switch (scenarioId) {
    case 'fire-explosion':
      return (
        <>
          <Fire />
          <FireExtinguisher />
        </>
      )

    case 'gas-leak-confined-space':
      return (
        <>
          <MineTunnel />
          <GasDetector />
        </>
      )

    case 'machinery-safety':
      return <Machinery />

    case 'electrical-hazard':
      return <ElectricalPanel />

    case 'dust-respiratory':
      return <DustMachine />

    case 'warehouse-loading':
      return <Warehouse />

    case 'roof-strata-control':
      return (
        <>
          <MineTunnel />
          <RoofStrata />
        </>
      )

    case 'working-at-height':
      return <WorkingAtHeight />

    case 'mine-haulage':
      return (
        <>
          <MineTunnel />
          <MineHaulage />
        </>
      )

    /*
     * A module with no scene of its own gets the worker and the room, and nothing
     * else. This used to return <Fire />, which meant the three modules added in
     * Phase 11 — roof strata, working at height and haulage — each opened their
     * briefing by showing the trainee a fire. Showing the wrong hazard is worse
     * than showing none: it teaches the wrong association before a word is read.
     */
    default:
      return null
  }
}

/* -------------------- THE WORKER, ACTING -------------------- */

/*
 * Where the worker ends up for each action, as an offset from where they start.
 *
 * Movement is the cheapest and clearest way to show a decision: leaving is
 * unmistakably different from backing off, which is unmistakably different from
 * walking toward a casualty. It also means the correct answer to "get out" is
 * demonstrated rather than asserted.
 */
const WORKER_MOVE = {
  [SCENE_ACTION.EVACUATE]: [3.6, 0],
  [SCENE_ACTION.RETREAT]: [-1.6, 0],
  [SCENE_ACTION.ASSIST]: [1.4, 0.6],
  [SCENE_ACTION.ENGAGE]: [1.2, 0],
  [SCENE_ACTION.EXTINGUISH]: [0.9, 0],
  [SCENE_ACTION.ISOLATE]: [1.1, 0.3],
  [SCENE_ACTION.ASSESS]: [0.5, 0],
}

/**
 * The existing Worker mesh, wrapped so it can move and gain a respirator.
 *
 * Wrapping rather than editing Worker keeps that mesh exactly as the AR overlay and
 * the rest of this file expect it, and means the animation lives in one place
 * instead of inside a component that has no business knowing about drill answers.
 */
function ActingWorker() {
  const group = useRef(null)
  const action = useSceneAction()
  const reduced = usePrefersReducedMotion()
  const walkSpeed = useMotionSpeed(2)

  const target = WORKER_MOVE[action?.kind] || [0, 0]

  useFrame((state, delta) => {
    const g = group.current
    if (!g) return
    const dt = Math.min(delta, 0.1)
    // Original Worker position was [-2.5, -0.5, 0]; offsets are relative to it.
    g.position.x = approach(g.position.x, -2.5 + target[0], dt, walkSpeed)
    g.position.z = approach(g.position.z, target[1], dt, walkSpeed)

    // Face the direction of travel, so walking backwards never happens.
    const facing = target[0] < -0.01 ? -1 : 1
    g.rotation.y = approach(g.rotation.y, facing > 0 ? 0 : Math.PI, dt, walkSpeed + 1)

    if (!reduced && Math.abs(g.position.x - (-2.5 + target[0])) > 0.05) {
      // A small bob while moving reads as walking rather than sliding.
      g.position.y = -0.5 + Math.abs(Math.sin(state.clock.elapsedTime * 9)) * 0.06
    } else {
      g.position.y = approach(g.position.y, -0.5, dt, 4)
    }
  })

  return (
    <group ref={group} position={[-2.5, -0.5, 0]}>
      <Worker position={[0, 0, 0]} />
      {action?.kind === SCENE_ACTION.PROTECT && <RespiratorOnWorker />}
    </group>
  )
}

/** Whichever effect the current action calls for. */
function ActionEffects() {
  const action = useSceneAction()
  if (!action) return null

  switch (action.kind) {
    // The jet only appears when the extinguisher was the RIGHT answer. Spraying CO2
    // and having the fire grow anyway would be an incoherent thing to show.
    case SCENE_ACTION.EXTINGUISH:
      return action.safe ? <AgentJet /> : null
    case SCENE_ACTION.SUPPRESS:
      return action.safe ? <WaterMist /> : null
    case SCENE_ACTION.ALERT:
      return <Beacon />
    case SCENE_ACTION.ISOLATE:
      return action.safe ? <Padlock /> : null
    default:
      return null
  }
}

/* -------------------- 3D WORLD -------------------- */

function IndustrialSurroundings() {
  return <group>
    {[-7, 7].map(x => <group key={x}>
      <mesh position={[x, 1.5, -4]}><boxGeometry args={[.3, 6, .3]} /><meshStandardMaterial color="#63757a" metalness={.5} roughness={.7} /></mesh>
      <mesh position={[x, -.8, -4]}><boxGeometry args={[.42, 1.4, .42]} /><meshStandardMaterial color="#cbb355" /></mesh>
    </group>)}
    <mesh position={[0, 4.3, -4]}><boxGeometry args={[14, .35, .3]} /><meshStandardMaterial color="#63757a" metalness={.5} roughness={.7} /></mesh>
    {[-4.8, 4.8].map(z => <mesh key={z} position={[0, -1.487, z]} rotation={[-Math.PI / 2, 0, 0]}>
      <planeGeometry args={[14, .06]} /><meshBasicMaterial color="#cbb355" />
    </mesh>)}
    <gridHelper args={[20, 20, '#718085', '#536165']} position={[0, -1.495, 0]} />
  </group>;
}

function Scene({ scenarioId, paused }) {
  const reduced = usePrefersReducedMotion()

  return (
    <>
      {/*
        Lighting rebalanced. The old setup was ambient 1.2 plus a hard key at
        intensity 2, which washed everything into flat colour — no form, no sense
        of a surface facing a light. Ambient is dropped to a fill, the key is
        softened, and a cool rim from behind separates objects from the
        background. The hazard scenes add their own local light on top of this
        (the fire, for instance, lights what is around it).
      */}
      <ambientLight intensity={0.85} />
      <hemisphereLight args={['#8fa3b8', '#2a2622', 0.5]} />

      {/* Key */}
      <directionalLight position={[5, 8, 5]} intensity={1.35} />
      {/* Cool rim from behind, so silhouettes read against the dark backdrop. */}
      <directionalLight position={[-6, 4, -6]} intensity={0.45} color="#9fc4e8" />

      <Floor />
      <IndustrialSurroundings />

      {/*
        Grounding. Without this, every object floats a little — the eye reads
        contact from the shadow, not from the geometry. Chosen over a full shadow
        map because it needs no per-mesh castShadow flags across ten meshes and
        costs a fraction as much on a mid-range phone.
      */}
      <ContactShadows
        position={[0, -1.49, 0]}
        scale={16}
        resolution={512}
        blur={2.4}
        opacity={0.55}
        far={4}
        color="#000000"
      />

      <ActingWorker />

      <ScenarioObjects scenarioId={scenarioId} />

      <ActionEffects />

      <OrbitControls
        enablePan={false}
        minDistance={5}
        maxDistance={11}
        maxPolarAngle={Math.PI / 2.05}
        /* Slow auto-drift so the scene reads as three-dimensional before the user
           has touched anything. Stops the moment they drag, and never runs for
           anyone who asked the OS to stop moving things. */
        autoRotate={!reduced && !paused}
        autoRotateSpeed={0.35}
      />
    </>
  )
}

/* -------------------- MAIN COMPONENT -------------------- */

/**
 * @param action  { kind, safe } from sceneAction.actionForChoice, or null before the
 *                worker has answered. Driving the scene from the chosen answer is
 *                what turns this from a rotating diagram into a simulation.
 */
function ResponsiveSceneCamera() {
  const camera = useThree(state => state.camera)
  const aspect = useThree(state => state.size.width / state.size.height)
  useEffect(() => {
    // Keep at least a 60-degree horizontal view on portrait phones.
    camera.fov = Math.max(45, Math.min(90, 2 * Math.atan(Math.tan(Math.PI / 6) / aspect) * 180 / Math.PI))
    camera.updateProjectionMatrix()
  }, [camera, aspect])
  return null
}

function SceneReadiness({ onReadyChange }) {
  const frames = useRef(0)
  const lost = useRef(false)
  const gl = useThree(state => state.gl)
  useEffect(() => {
    frames.current = 0
    lost.current = false
    onReadyChange?.(false)
    const onLost = event => {
      event.preventDefault()
      lost.current = true
      frames.current = 0
      onReadyChange?.(false)
    }
    const onRestored = () => { lost.current = false; frames.current = 0 }
    const canvas = gl.domElement
    canvas.addEventListener('webglcontextlost', onLost)
    canvas.addEventListener('webglcontextrestored', onRestored)
    return () => {
      canvas.removeEventListener('webglcontextlost', onLost)
      canvas.removeEventListener('webglcontextrestored', onRestored)
      onReadyChange?.(false)
    }
  }, [gl, onReadyChange])
  useFrame(() => {
    if (lost.current || gl.getContext().isContextLost()) return
    // The second frame confirms the first scene render has completed.
    if (++frames.current === 2) onReadyChange?.(true)
  })
  return null
}

function CssSafetyFallback({ scenarioId, action, paused }) {
  const hazard = {
    'fire-explosion': { kind: 'fire', color: '#ff8a2a', accent: '#f44336' },
    'gas-leak-confined-space': { kind: 'gas', color: '#82d7d0', accent: '#795548' },
    'machinery-safety': { kind: 'machine', color: '#ffc247', accent: '#455a64' },
    'working-at-height': { kind: 'height', color: '#b3a2ff', accent: '#7f8996' },
    'electrical-hazard': { kind: 'electric', color: '#ffe066', accent: '#607d8b' },
  }[scenarioId] || { kind: 'fire', color: '#ff8a2a', accent: '#f44336' }
  const alert = action?.kind === SCENE_ACTION.ALERT
  const retreat = action?.kind === SCENE_ACTION.RETREAT
  return (
    <div aria-hidden="true" style={{
      position: 'absolute',
      inset: 0,
      overflow: 'hidden',
      zIndex: 2,
      background: 'radial-gradient(120% 80% at 50% 5%, #263238 0%, #12171a 55%, #0b0f11 100%)',
      perspective: '720px',
      pointerEvents: 'none',
    }}>
      <div style={{
        position: 'absolute',
        left: '-20%',
        right: '-20%',
        bottom: '-12%',
        height: '62%',
        transform: 'rotateX(62deg)',
        transformOrigin: '50% 100%',
        backgroundImage:
          'linear-gradient(rgba(170,190,195,.24) 1px, transparent 1px), linear-gradient(90deg, rgba(170,190,195,.24) 1px, transparent 1px), linear-gradient(90deg, transparent 0 48%, #d9bd55 48% 49.2%, transparent 49.2%)',
        backgroundSize: '38px 38px, 38px 38px, 100% 100%',
        backgroundColor: '#334246',
      }} />
      <div style={{
        position: 'absolute',
        left: '14%',
        top: '30%',
        width: 92,
        height: 170,
        transform: `translateX(${retreat ? '-34px' : '0'}) rotateY(${retreat ? '26deg' : '-12deg'})`,
        transition: 'transform 420ms ease',
      }}>
        <div style={{ position: 'absolute', left: 32, top: 0, width: 42, height: 42, borderRadius: '50%', background: '#d8a16b' }} />
        <div style={{ position: 'absolute', left: 24, top: -6, width: 58, height: 20, borderRadius: '50% 50% 8px 8px', background: '#f5c518' }} />
        <div style={{ position: 'absolute', left: 18, top: 42, width: 68, height: 86, borderRadius: 8, background: '#263238', boxShadow: 'inset 0 0 0 10px #ff9800' }} />
        <div style={{ position: 'absolute', left: 36, top: 58, width: 32, height: 8, background: '#e6edbe' }} />
        <div style={{ position: 'absolute', left: 22, top: 126, width: 22, height: 44, background: '#17202a' }} />
        <div style={{ position: 'absolute', left: 62, top: 126, width: 22, height: 44, background: '#17202a' }} />
      </div>
      <div style={{
        position: 'absolute',
        right: hazard.kind === 'height' ? '18%' : '20%',
        top: hazard.kind === 'height' ? '20%' : '42%',
        width: hazard.kind === 'machine' ? 150 : 92,
        height: hazard.kind === 'height' ? 190 : 120,
        transform: 'rotateX(8deg) rotateY(-20deg)',
        filter: alert ? 'drop-shadow(0 0 22px #ffb020)' : 'none',
      }}>
        {hazard.kind === 'fire' && <div>
          <div style={{ position: 'absolute', left: 10, top: 18, width: 78, height: 100, clipPath: 'polygon(50% 0, 80% 45%, 62% 100%, 36% 100%, 18% 44%)', background: 'linear-gradient(#ffd45a, #ff6d00)', borderRadius: 14 }} />
          <div style={{ position: 'absolute', left: 54, top: 46, width: 28, height: 96, borderRadius: 8, background: '#c62828' }} />
        </div>}
        {hazard.kind === 'gas' && <div>
          <div style={{ position: 'absolute', inset: '20px 8px', borderRadius: 14, background: hazard.accent, boxShadow: 'inset 0 0 0 10px #4e342e' }} />
          {[0, 1, 2, 3].map(i => <div key={i} style={{ position: 'absolute', left: 12 + i * 18, top: 4 + i * 7, width: 34, height: 34, borderRadius: '50%', background: hazard.color, opacity: .55 }} />)}
        </div>}
        {hazard.kind === 'machine' && <div>
          <div style={{ position: 'absolute', left: 0, top: 28, width: 150, height: 82, borderRadius: 8, background: hazard.accent }} />
          <div style={{ position: 'absolute', left: 18, top: 46, width: 114, height: 12, background: '#ffb020' }} />
          <div style={{ position: 'absolute', left: 56, top: 0, width: 42, height: 46, borderRadius: '50%', background: '#90a4ae' }} />
        </div>}
        {hazard.kind === 'height' && <div>
          <div style={{ position: 'absolute', left: 8, top: 28, width: 90, height: 14, background: '#a98a5c' }} />
          <div style={{ position: 'absolute', left: 14, top: 42, width: 10, height: 148, background: '#7f8996' }} />
          <div style={{ position: 'absolute', left: 76, top: 42, width: 10, height: 148, background: '#7f8996' }} />
          <div style={{ position: 'absolute', left: 54, top: 0, width: 42, height: 42, borderRadius: '50%', border: '9px solid #2e7d4f' }} />
        </div>}
        {hazard.kind === 'electric' && <div>
          <div style={{ position: 'absolute', left: 0, top: 12, width: 112, height: 124, borderRadius: 8, background: hazard.accent }} />
          <div style={{ position: 'absolute', left: 28, top: 34, width: 56, height: 62, clipPath: 'polygon(46% 0, 78% 0, 56% 42%, 86% 42%, 32% 100%, 46% 55%, 18% 55%)', background: '#ffe066' }} />
        </div>}
      </div>
      <div style={{
        position: 'absolute',
        left: '50%',
        top: '12%',
        width: 70,
        height: 70,
        borderRadius: '50%',
        background: alert ? '#ffb020' : '#3a3f45',
        boxShadow: alert && !paused ? '0 0 42px #ffb020' : 'none',
        opacity: alert ? 1 : .55,
      }} />
    </div>
  )
}

export default function SafetyScene3D({ scenarioId, action = null, onReadyChange, paused = false, forceDomFallback = false }) {
  return (
    <div
      style={{
        width: '100%',
        height: '420px',
        marginBottom: '24px',
        overflow: 'hidden',
        /* A gradient rather than a flat fill, so the floor fades into the backdrop
           instead of meeting it at a hard visible line. CSS, so it costs nothing
           on the GPU that is already drawing the scene. */
        background: 'radial-gradient(120% 90% at 50% 12%, #1d242a 0%, #12171a 55%, #0c1012 100%)',
        position: 'relative',
      }}
    >
      {forceDomFallback && <CssSafetyFallback scenarioId={scenarioId} action={action} paused={paused} />}
      {forceDomFallback ? <canvas width="1" height="1" style={{ display: 'none' }} /> : (
      <Canvas
        style={{ position: 'relative', zIndex: 1 }}
        frameloop={paused ? 'demand' : 'always'}
        camera={{
          position: [5, 3, 7],
          fov: 45,
        }}
        /* Capped pixel ratio: a 3x phone display would otherwise render nine times
           the pixels for a briefing diagram that gains nothing from it. */
        dpr={[1, 1.75]}
      >
        <ResponsiveSceneCamera />
        <Suspense fallback={null}>
          <SceneActionContext.Provider value={action}>
            <Scene scenarioId={scenarioId} paused={paused} />
            <SceneReadiness onReadyChange={onReadyChange} />
          </SceneActionContext.Provider>
        </Suspense>
      </Canvas>
      )}

    </div>
  )
}
